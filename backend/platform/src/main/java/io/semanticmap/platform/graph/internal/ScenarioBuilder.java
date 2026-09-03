package io.semanticmap.platform.graph.internal;

import io.semanticmap.platform.shared.Db;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScenarioBuilder {
    private final Db db;
    private final int maxDepth;
    private final int maxNodes;

    private record Visit(Map<String, Object> node, int depth, List<Object> pathEvidence, double confidence) {}

    private static final Set<String> ASYNC = Set.of("EVENT_PUBLICATION", "MESSAGE_CONSUMER", "EXTERNAL_CALL");

    public ScenarioBuilder(
            Db db,
            @Value("${semantic.scenario.max-depth:8}") int maxDepth,
            @Value("${semantic.scenario.max-nodes:120}") int maxNodes) {
        this.db = db;
        this.maxDepth = Math.clamp(maxDepth, 1, 16);
        this.maxNodes = Math.clamp(maxNodes, 1, 500);
    }

    public void build(UUID org, UUID project, UUID revision, UUID run) {
        UUID cursor = new UUID(0, 0);
        while (true) {
            var entries = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND kind IN ('ENDPOINT','SCHEDULED_JOB','MESSAGE_CONSUMER','COMMAND_ENTRY_POINT','UI_ACTION') AND id>? ORDER BY id LIMIT 100",
                    org,
                    project,
                    run,
                    cursor);
            if (entries.isEmpty()) return;
            for (var entry : entries) buildOne(org, project, revision, run, entry);
            cursor = Semantics.uuid(entries.getLast().get("id"));
        }
    }

    private void buildOne(UUID org, UUID project, UUID revision, UUID run, Map<String, Object> entry) {
        var queue = new ArrayDeque<Visit>();
        queue.add(new Visit(
                entry,
                0,
                new ArrayList<>(Semantics.list(entry.get("evidenceIds"))),
                ((Number) entry.get("confidence")).doubleValue()));
        var seen = new HashSet<UUID>();
        seen.add(Semantics.uuid(entry.get("id")));
        var members = new ArrayList<Visit>();
        var async = new ArrayList<Object>();
        var unresolved = new ArrayList<Object>();
        boolean truncated = false;
        while (!queue.isEmpty()) {
            Visit visit = queue.removeFirst();
            members.add(visit);
            UUID id = Semantics.uuid(visit.node().get("id"));
            if (Boolean.TRUE.equals(visit.node().get("unresolved"))) {
                unresolved.add(id.toString());
                continue;
            }
            if (visit.depth() > 0 && ASYNC.contains(visit.node().get("kind"))) {
                async.add(id.toString());
                continue;
            }
            var next = db.rows(
                    "SELECT n.*,e.evidence_ids AS path_evidence,e.confidence AS relationship_confidence FROM semantic_edge e JOIN semantic_node n ON n.id=e.target_id AND n.organization_id=e.organization_id AND n.project_id=e.project_id AND n.analysis_run_id=e.analysis_run_id WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=? AND e.source_id=? AND e.kind IN ('CALLS','ENTRY_TO','TRIGGERS','CONTAINS','GUARDED_BY','AUTHORIZED_BY','VALIDATES','READS','WRITES','FILTERS','TRANSFORMS','EMITS','CALLS_EXTERNAL','THROWS','RETURNS','CHANGES_STATUS','BRANCH_TRUE','BRANCH_FALSE','VERIFIED_BY','PRECEDES') ORDER BY n.stable_key,e.kind LIMIT ?",
                    org,
                    project,
                    run,
                    id,
                    maxNodes + 1);
            for (var node : next) {
                UUID nextId = Semantics.uuid(node.get("id"));
                if (seen.contains(nextId)) continue;
                if (node.get("kind").equals("TECHNICAL_GUARD")
                        || Boolean.TRUE.equals(
                                Semantics.map(node.get("properties")).get("frameworkInternal"))) continue;
                if (visit.depth() >= maxDepth || seen.size() >= maxNodes) {
                    truncated = true;
                    continue;
                }
                seen.add(nextId);
                var proof = Semantics.union(
                        visit.pathEvidence(),
                        Semantics.union(
                                Semantics.list(node.get("pathEvidence")), Semantics.list(node.get("evidenceIds"))));
                queue.addLast(new Visit(
                        node,
                        visit.depth() + 1,
                        proof,
                        Math.min(
                                visit.confidence(),
                                Math.min(
                                        ((Number) node.get("confidence")).doubleValue(),
                                        ((Number) node.get("relationshipConfidence")).doubleValue()))));
            }
        }
        var model = new LinkedHashMap<String, Object>();
        model.put("entryPoint", entry.get("id"));
        var props = Semantics.map(entry.get("properties"));
        model.put("actors", props.getOrDefault("actors", List.of()));
        model.put("inputContract", props.getOrDefault("inputContract", Map.of()));
        model.put("preconditions", props.getOrDefault("preconditions", List.of()));
        model.put("asyncBoundaries", async);
        model.put("unresolved", unresolved);
        var byKind = new LinkedHashMap<String, List<Object>>();
        for (var member : members)
            byKind.computeIfAbsent(member.node().get("kind").toString(), ignored -> new ArrayList<>())
                    .add(member.node().get("id"));
        model.put("membersByKind", byKind);
        model.put("truncated", truncated);
        model.put("maxDepth", maxDepth);
        model.put("maxNodes", maxNodes);
        // Only observed graph reachability is represented; path order is not invented.
        model.put("pathOrderKnown", false);
        double confidence =
                members.stream().mapToDouble(Visit::confidence).min().orElse(0);
        model.put("confidence", confidence);
        String key = "scenario:" + entry.get("stableKey");
        UUID nodeId = UUID.randomUUID(), scenarioId = UUID.randomUUID();
        var structural = Map.of(
                "entryPointKey",
                entry.get("stableKey"),
                "memberKeys",
                members.stream()
                        .map(v -> v.node().get("stableKey"))
                        .sorted((a, b) -> a.toString().compareTo(b.toString()))
                        .toList(),
                "memberFingerprints",
                members.stream()
                        .map(v -> v.node().get("stableKey") + ":" + v.node().get("fingerprint"))
                        .sorted()
                        .toList());
        var evidenceIds = members.stream()
                .flatMap(member -> member.pathEvidence().stream())
                .map(Object::toString)
                .distinct()
                .sorted()
                .toList();
        var factIds = members.stream()
                .flatMap(member -> Semantics.strings(member.node().get("sourceFactIds")).stream())
                .distinct()
                .sorted()
                .toList();
        var evidenceHashes = db.rows(
                "SELECT DISTINCT snippet_hash,analyzer_id,analyzer_version,image_digest,origin FROM evidence WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id::text IN (SELECT jsonb_array_elements_text(?::jsonb)) ORDER BY snippet_hash,analyzer_id,analyzer_version,image_digest,origin",
                org,
                project,
                run,
                db.json(evidenceIds));
        String evidenceFingerprint = Semantics.hash(evidenceHashes);
        String fingerprint = Semantics.hash(List.of(structural, evidenceFingerprint));
        db.update(
                "INSERT INTO semantic_node(id,organization_id,project_id,revision_id,analysis_run_id,stable_key,kind,name,label,confidence,support_level,properties,source_fact_ids,evidence_ids,fingerprint,structural_fingerprint,evidence_fingerprint) VALUES (?,?,?,?,?,?,'BUSINESS_SCENARIO',?,?,?,'SUPPORTED',?::jsonb,?::jsonb,?::jsonb,?,?,?)",
                nodeId,
                org,
                project,
                revision,
                run,
                key,
                entry.get("label"),
                entry.get("label"),
                confidence,
                db.json(structural),
                db.json(factIds),
                db.json(evidenceIds),
                fingerprint,
                Semantics.hash(structural),
                evidenceFingerprint);
        db.update(
                "INSERT INTO business_scenario(id,organization_id,project_id,analysis_run_id,entry_node_id,node_id,model,truncated) VALUES (?,?,?,?,?,?,?::jsonb,?)",
                scenarioId,
                org,
                project,
                run,
                entry.get("id"),
                nodeId,
                db.json(model),
                truncated);
        db.update(
                "INSERT INTO semantic_edge(id,organization_id,project_id,analysis_run_id,source_id,target_id,kind,label,confidence,evidence_ids) VALUES (?,?,?,?,?,?,'ENTRY_TO','ENTRY_TO',?,?::jsonb)",
                UUID.randomUUID(),
                org,
                project,
                run,
                nodeId,
                entry.get("id"),
                entry.get("confidence"),
                db.json(entry.get("evidenceIds")));
        for (Visit member : members) {
            db.update(
                    "INSERT INTO business_scenario_member(scenario_id,node_id,organization_id,project_id,analysis_run_id,depth,role) VALUES (?,?,?,?,?,?,?)",
                    scenarioId,
                    member.node().get("id"),
                    org,
                    project,
                    run,
                    member.depth(),
                    member.node().get("kind"));
            if (member.depth() > 0)
                db.update(
                        "INSERT INTO semantic_edge(id,organization_id,project_id,analysis_run_id,source_id,target_id,kind,label,confidence,evidence_ids,properties) VALUES (?,?,?,?,?,?,'PART_OF_SCENARIO','PART_OF_SCENARIO',?,?::jsonb,?::jsonb)",
                        UUID.randomUUID(),
                        org,
                        project,
                        run,
                        member.node().get("id"),
                        nodeId,
                        member.confidence(),
                        db.json(member.pathEvidence()),
                        db.json(Map.of("derivation", "BOUNDED_REACHABILITY", "depth", member.depth())));
        }
    }
}
