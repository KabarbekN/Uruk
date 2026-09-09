package io.semanticmap.platform.graph.internal;

import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class GraphBuilder {
    private final Db db;
    private final ScenarioBuilder scenarios;
    private final RuleEngine rules = new RuleEngine();

    public GraphBuilder(Db db, ScenarioBuilder scenarios) {
        this.db = db;
        this.scenarios = scenarios;
    }

    public void build(UUID org, UUID project, UUID revision, UUID run) {
        db.one(
                "SELECT id FROM analysis_run WHERE id=? AND organization_id=? AND project_id=? AND revision_id=? FOR UPDATE",
                run,
                org,
                project,
                revision);
        eachFact(org, project, run, fact -> {
            if ("RELATION".equals(fact.get("kind"))) return;
            var properties = new LinkedHashMap<>(
                    Semantics.map(Semantics.map(fact.get("payload")).get("properties")));
            addProvenance(properties, fact);
            String kind = fact.get("kind").toString();
            String normalizedKind = Semantics.nodeKind(kind, properties);
            if (!normalizedKind.equals(kind)) properties.put("sourceKind", kind);
            kind = normalizedKind;
            double assertionCeiling = 1;
            if (Semantics.RULE_KINDS.contains(kind)) {
                var rule = rules.classify(kind, properties);
                properties.putAll(rule.model());
                properties.put("sourceKind", kind);
                if ("UNKNOWN_EXPRESSION".equals(rule.condition().get("type")) || "UNKNOWN_RULE".equals(rule.category()))
                    assertionCeiling = .45;
                else if (!kind.equals(rule.category()) && !kind.equals("BUSINESS_RULE")) assertionCeiling = .85;
                kind = rule.category().equals("BUSINESS_CONSTRAINT") ? "BUSINESS_RULE" : rule.category();
            }
            List<Map<String, Object>> evidence = factEvidence(org, project, run, fact.get("id"));
            if (evidence.isEmpty()) return;
            properties.putIfAbsent("filePath", evidence.getFirst().get("filePath"));
            String key = fact.get("stableKey").toString();
            List<Object> evidenceIds = new ArrayList<>(
                    evidence.stream().map(e -> e.get("id").toString()).toList());
            List<Object> factIds = new ArrayList<>(List.of(fact.get("id").toString()));
            double confidence = Math.min(assertionCeiling, ((Number) fact.get("confidence")).doubleValue());
            var existing = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?",
                    org,
                    project,
                    run,
                    key);
            if (!existing.isEmpty()) {
                var previous = existing.getFirst();
                evidenceIds = Semantics.union(Semantics.list(previous.get("evidenceIds")), evidenceIds);
                factIds = Semantics.union(Semantics.list(previous.get("sourceFactIds")), factIds);
                if (!Boolean.TRUE.equals(previous.get("unresolved"))) {
                    properties = mergeProperties(Semantics.map(previous.get("properties")), properties);
                    if (properties.containsKey("factAlternatives")) confidence = Math.min(confidence, .45);
                    kind = previous.get("kind").toString();
                    confidence = Math.min(confidence, ((Number) previous.get("confidence")).doubleValue());
                }
            }
            var allEvidence = db.rows(
                    "SELECT DISTINCT snippet_hash,analyzer_id,analyzer_version,image_digest,origin FROM evidence WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id IN (SELECT (jsonb_array_elements_text(?::jsonb))::uuid) ORDER BY snippet_hash,analyzer_id,analyzer_version,image_digest,origin",
                    org,
                    project,
                    run,
                    db.json(evidenceIds));
            String evidenceFingerprint = Semantics.hash(allEvidence);
            String fingerprint = Semantics.hash(List.of(Semantics.fingerprint(kind, properties), evidenceFingerprint));
            String label = Semantics.text(properties, "name", key);
            UUID id = existing.isEmpty()
                    ? UUID.randomUUID()
                    : Semantics.uuid(existing.getFirst().get("id"));
            db.update(
                    "INSERT INTO semantic_node(id,organization_id,project_id,revision_id,analysis_run_id,stable_key,kind,name,label,subtitle,confidence,support_level,properties,source_fact_ids,evidence_ids,fingerprint,structural_fingerprint,evidence_fingerprint) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?) ON CONFLICT (organization_id,project_id,analysis_run_id,stable_key) DO UPDATE SET kind=excluded.kind,name=excluded.name,label=excluded.label,subtitle=excluded.subtitle,properties=excluded.properties,source_fact_ids=excluded.source_fact_ids,evidence_ids=excluded.evidence_ids,confidence=excluded.confidence,support_level=excluded.support_level,fingerprint=excluded.fingerprint,structural_fingerprint=excluded.structural_fingerprint,evidence_fingerprint=excluded.evidence_fingerprint,unresolved=false",
                    id,
                    org,
                    project,
                    revision,
                    run,
                    key,
                    kind,
                    label,
                    label,
                    Semantics.text(properties, "description", ""),
                    confidence,
                    confidence >= .9 ? "PROVEN" : "SUPPORTED",
                    db.json(properties),
                    db.json(factIds),
                    db.json(evidenceIds),
                    fingerprint,
                    Semantics.structural(kind, properties),
                    evidenceFingerprint);
        });
        eachFact(org, project, run, fact -> {
            var props = Semantics.map(Semantics.map(fact.get("payload")).get("properties"));
            var evidenceIds = factEvidence(org, project, run, fact.get("id")).stream()
                    .map(e -> e.get("id").toString())
                    .toList();
            if (evidenceIds.isEmpty()) return;
            String source = null, target = null, kind = null;
            if ("RELATION".equals(fact.get("kind"))) {
                source = Semantics.text(props, "sourceKey", "");
                target = Semantics.text(props, "targetKey", "");
                kind = Semantics.text(props, "edgeKind", "DEPENDS_ON");
            } else if (props.get("ownerKey") instanceof String owner && !owner.isBlank()) {
                source = owner;
                target = fact.get("stableKey").toString();
                kind = "CONTAINS";
            }
            if (source == null || source.isBlank() || target == null || target.isBlank()) return;
            var from = resolve(org, project, revision, run, source, evidenceIds);
            var to = resolve(org, project, revision, run, target, evidenceIds);
            double confidence = Boolean.TRUE.equals(from.get("unresolved")) || Boolean.TRUE.equals(to.get("unresolved"))
                    ? 0
                    : ((Number) fact.get("confidence")).doubleValue();
            edge(
                    org,
                    project,
                    run,
                    Semantics.uuid(from.get("id")),
                    Semantics.uuid(to.get("id")),
                    kind,
                    evidenceIds,
                    confidence,
                    props);
        });
        UUID cursor = new UUID(0, 0);
        while (true) {
            var nodes = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id>? ORDER BY id LIMIT 500",
                    org,
                    project,
                    run,
                    cursor);
            if (nodes.isEmpty()) break;
            for (var node : nodes) {
                var props = Semantics.map(node.get("properties"));
                if (!props.containsKey("normalizedCondition")) continue;
                var existingAssertion = db.rows(
                        "SELECT id FROM assertion WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=?",
                        org,
                        project,
                        run,
                        node.get("id"));
                UUID assertionId = existingAssertion.isEmpty()
                        ? UUID.randomUUID()
                        : Semantics.uuid(existingAssertion.getFirst().get("id"));
                if (!existingAssertion.isEmpty())
                    db.update(
                            "DELETE FROM assertion_evidence WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND assertion_id=?",
                            org,
                            project,
                            run,
                            assertionId);
                db.update(
                        "INSERT INTO assertion(id,organization_id,project_id,analysis_run_id,node_id,category,normalized_condition,true_outcomes,false_outcomes,score_breakdown,model,confidence) VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?) ON CONFLICT (node_id) DO UPDATE SET category=excluded.category,normalized_condition=excluded.normalized_condition,true_outcomes=excluded.true_outcomes,false_outcomes=excluded.false_outcomes,score_breakdown=excluded.score_breakdown,model=excluded.model,confidence=excluded.confidence",
                        assertionId,
                        org,
                        project,
                        run,
                        node.get("id"),
                        props.get("category"),
                        db.json(props.get("normalizedCondition")),
                        db.json(props.get("trueOutcomes")),
                        db.json(props.get("falseOutcomes")),
                        db.json(props.get("scoreBreakdown")),
                        db.json(props),
                        node.get("confidence"));
                for (String id : Semantics.strings(node.get("evidenceIds")))
                    db.update(
                            "INSERT INTO assertion_evidence(assertion_id,evidence_id,organization_id,project_id,analysis_run_id) VALUES (?,?,?,?,?)",
                            assertionId,
                            UUID.fromString(id),
                            org,
                            project,
                            run);
            }
            cursor = Semantics.uuid(nodes.getLast().get("id"));
        }
        scenarios.build(org, project, revision, run);
    }

    private void eachFact(UUID org, UUID project, UUID run, Consumer<Map<String, Object>> action) {
        int offset = 0;
        while (true) {
            var page = db.rows(
                    "SELECT f.*,e.analyzer_key,e.version AS analyzer_version,e.image_digest,c.root_path AS component_path,c.frameworks,c.languages FROM raw_fact f JOIN analyzer_execution e ON e.id=f.analyzer_execution_id AND e.organization_id=f.organization_id AND e.project_id=f.project_id AND e.analysis_run_id=f.analysis_run_id JOIN technology_component c ON c.id=e.component_id AND c.organization_id=e.organization_id AND c.project_id=e.project_id AND c.analysis_run_id=e.analysis_run_id WHERE f.organization_id=? AND f.project_id=? AND f.analysis_run_id=? ORDER BY f.stable_key,CASE f.origin WHEN 'STATIC_EXACT' THEN 0 WHEN 'STATIC_RESOLVED' THEN 1 WHEN 'STATIC_TYPED' THEN 1 WHEN 'DATABASE_DERIVED' THEN 1 WHEN 'RUNTIME_OBSERVED' THEN 2 WHEN 'FRAMEWORK_DERIVED' THEN 3 ELSE 4 END,e.analyzer_key,e.version,f.fact_id,f.fingerprint LIMIT 500 OFFSET ?",
                    org,
                    project,
                    run,
                    offset);
            if (page.isEmpty()) return;
            page.forEach(action);
            offset += page.size();
        }
    }

    public static LinkedHashMap<String, Object> mergeProperties(
            Map<String, Object> preferred, Map<String, Object> incoming) {
        var base = new LinkedHashMap<>(preferred);
        Object alternatives = base.remove("factAlternatives");
        var comparable = new LinkedHashMap<>(base);
        comparable.remove("factConflict");
        if (Semantics.canonical(Semantics.behavioral(comparable))
                .equals(Semantics.canonical(Semantics.behavioral(incoming)))) {
            if (alternatives != null) base.put("factAlternatives", alternatives);
            mergeProvenance(base, incoming);
            return base;
        }
        var variants = new java.util.TreeMap<String, Object>();
        for (Object item : Semantics.list(alternatives)) variants.put(Semantics.hash(item), item);
        variants.put(Semantics.hash(incoming), incoming);
        base.put("factAlternatives", List.copyOf(variants.values()));
        base.put("factConflict", true);
        mergeProvenance(base, incoming);
        return base;
    }

    private static void addProvenance(Map<String, Object> properties, Map<String, Object> fact) {
        var provenance = new LinkedHashMap<String, Object>();
        provenance.put("analyzerId", fact.get("analyzerKey"));
        provenance.put("analyzerVersion", fact.get("analyzerVersion"));
        provenance.put("imageDigest", fact.get("imageDigest"));
        provenance.put("origin", fact.get("origin"));
        provenance.put("componentPath", fact.get("componentPath"));
        provenance.put(
                "frameworks",
                Semantics.strings(fact.get("frameworks")).stream().sorted().toList());
        provenance.put(
                "languages",
                Semantics.strings(fact.get("languages")).stream().sorted().toList());
        properties.put("provenance", List.of(provenance));
        mergeProvenance(properties, Map.of());
        if (!properties.containsKey("sourceLayer")) {
            var frameworks = Semantics.strings(fact.get("frameworks"));
            boolean backend = frameworks.stream()
                    .anyMatch(f -> List.of("SPRING_BOOT", "SPRING_MVC", "SPRING", "EXPRESS", "NESTJS")
                            .contains(f));
            boolean frontend = frameworks.stream()
                    .anyMatch(f -> List.of("REACT", "VUE", "ANGULAR").contains(f));
            if ("DATABASE_DERIVED".equals(fact.get("origin"))) properties.put("sourceLayer", "DATABASE");
            else if (backend && !frontend) properties.put("sourceLayer", "BACKEND");
            else if (frontend && !backend) properties.put("sourceLayer", "FRONTEND");
        }
    }

    private static void mergeProvenance(Map<String, Object> preferred, Map<String, Object> incoming) {
        var records = new java.util.TreeMap<String, Object>();
        for (Object item : Semantics.union(
                Semantics.list(preferred.get("provenance")), Semantics.list(incoming.get("provenance"))))
            records.put(Semantics.hash(item), item);
        if (records.isEmpty()) return;
        preferred.put("provenance", List.copyOf(records.values()));
        for (var field : Map.of("analyzers", "analyzerId", "origins", "origin", "componentPaths", "componentPath")
                .entrySet())
            preferred.put(
                    field.getKey(),
                    records.values().stream()
                            .map(Semantics::map)
                            .map(p -> p.get(field.getValue()))
                            .filter(java.util.Objects::nonNull)
                            .map(Object::toString)
                            .distinct()
                            .sorted()
                            .toList());
        preferred.put(
                "frameworks",
                records.values().stream()
                        .map(Semantics::map)
                        .flatMap(p -> Semantics.strings(p.get("frameworks")).stream())
                        .distinct()
                        .sorted()
                        .toList());
    }

    private List<Map<String, Object>> factEvidence(UUID org, UUID project, UUID run, Object fact) {
        return db.rows(
                "SELECT id,file_path,snippet_hash FROM evidence WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND raw_fact_id=? AND verified ORDER BY file_path,start_line,end_line,snippet_hash,id",
                org,
                project,
                run,
                fact);
    }

    private Map<String, Object> resolve(UUID org, UUID project, UUID revision, UUID run, String key, List<?> evidence) {
        var found = db.rows(
                "SELECT id,unresolved FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?",
                org,
                project,
                run,
                key);
        if (!found.isEmpty()) return found.getFirst();
        UUID id = UUID.randomUUID();
        String fingerprint = Semantics.hash(key);
        db.update(
                "INSERT INTO semantic_node(id,organization_id,project_id,revision_id,analysis_run_id,stable_key,kind,name,label,confidence,support_level,properties,evidence_ids,fingerprint,structural_fingerprint,evidence_fingerprint,unresolved) VALUES (?,?,?,?,?,?,'UNRESOLVED_SYMBOL',?,?,0,'UNRESOLVED',?::jsonb,?::jsonb,?,?,?,true)",
                id,
                org,
                project,
                revision,
                run,
                key,
                key,
                key,
                db.json(Map.of("resolutionStatus", "UNRESOLVED", "referenceKey", key)),
                db.json(evidence),
                fingerprint,
                fingerprint,
                fingerprint);
        return Map.of("id", id, "unresolved", true);
    }

    public void edge(
            UUID org,
            UUID project,
            UUID run,
            UUID source,
            UUID target,
            String kind,
            List<?> evidence,
            double confidence,
            Map<String, Object> properties) {
        db.update(
                "INSERT INTO semantic_edge(id,organization_id,project_id,analysis_run_id,source_id,target_id,kind,label,confidence,evidence_ids,properties) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT (organization_id,project_id,analysis_run_id,source_id,target_id,kind) DO UPDATE SET evidence_ids=(SELECT jsonb_agg(DISTINCT v) FROM jsonb_array_elements(semantic_edge.evidence_ids || excluded.evidence_ids) v), confidence=least(semantic_edge.confidence,excluded.confidence)",
                UUID.randomUUID(),
                org,
                project,
                run,
                source,
                target,
                kind,
                kind,
                confidence,
                db.json(evidence),
                db.json(properties));
    }
}
