package io.semanticmap.platform.graph.internal;

import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class SemanticDiffer {
    public record Change(String changeType, String impactType) {}

    private final Db db;

    public SemanticDiffer(Db db) {
        this.db = db;
    }

    public void diff(UUID org, UUID project, UUID from, UUID to) {
        db.one("SELECT id FROM analysis_run WHERE id=? AND organization_id=? AND project_id=?", from, org, project);
        var targetRun = db.one(
                "SELECT id,baseline_run_id FROM analysis_run WHERE id=? AND organization_id=? AND project_id=? FOR UPDATE",
                to,
                org,
                project);
        boolean baselineComparison = Objects.equals(from, targetRun.get("baselineRunId"));
        if (from.equals(to)) return;
        Set<UUID> matched = new HashSet<>();
        UUID cursor = new UUID(0, 0);
        while (true) {
            var beforePage = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id>? ORDER BY id LIMIT 500",
                    org,
                    project,
                    from,
                    cursor);
            if (beforePage.isEmpty()) break;
            for (var before : beforePage) {
                var after = match(org, project, to, before, matched);
                if (after == null) {
                    save(org, project, from, to, before, null, new Change("REMOVED", effect(before, "REMOVED")));
                    continue;
                }
                matched.add(Semantics.uuid(after.get("id")));
                for (var change : compare(before, after)) save(org, project, from, to, before, after, change);
                if (baselineComparison) carryReview(org, project, from, to, before, after);
            }
            cursor = Semantics.uuid(beforePage.getLast().get("id"));
        }
        cursor = new UUID(0, 0);
        while (true) {
            var afterPage = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND id>? ORDER BY id LIMIT 500",
                    org,
                    project,
                    to,
                    cursor);
            if (afterPage.isEmpty()) break;
            for (var after : afterPage)
                if (!matched.contains(Semantics.uuid(after.get("id"))))
                    save(org, project, from, to, null, after, new Change("ADDED", effect(after, "ADDED")));
            cursor = Semantics.uuid(afterPage.getLast().get("id"));
        }
        diffEdges(org, project, from, to);
    }

    private Map<String, Object> match(UUID org, UUID project, UUID to, Map<String, Object> before, Set<UUID> matched) {
        var exact = db.rows(
                "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND stable_key=?",
                org,
                project,
                to,
                before.get("stableKey"));
        if (!exact.isEmpty()
                && !matched.contains(Semantics.uuid(exact.getFirst().get("id")))) return exact.getFirst();
        var props = Semantics.map(before.get("properties"));
        if (props.get("symbolSignature") != null) {
            var symbols = db.rows(
                    "SELECT * FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND kind=? AND properties->>'symbolSignature'=? LIMIT 2",
                    org,
                    project,
                    to,
                    before.get("kind"),
                    props.get("symbolSignature"));
            if (symbols.size() == 1
                    && !matched.contains(Semantics.uuid(symbols.getFirst().get("id")))) return symbols.getFirst();
        }
        var shapes = db.rows(
                "SELECT n.* FROM semantic_node n WHERE n.organization_id=? AND n.project_id=? AND n.analysis_run_id=? AND n.kind=? AND n.structural_fingerprint=? AND NOT EXISTS (SELECT 1 FROM semantic_node old WHERE old.organization_id=n.organization_id AND old.project_id=n.project_id AND old.analysis_run_id=? AND old.stable_key=n.stable_key) LIMIT 2",
                org,
                project,
                to,
                before.get("kind"),
                before.get("structuralFingerprint"),
                before.get("analysisRunId"));
        if (shapes.size() != 1
                || matched.contains(Semantics.uuid(shapes.getFirst().get("id")))) return null;
        var oldShapes = db.rows(
                "SELECT id FROM semantic_node WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND kind=? AND structural_fingerprint=? LIMIT 2",
                org,
                project,
                before.get("analysisRunId"),
                before.get("kind"),
                before.get("structuralFingerprint"));
        return oldShapes.size() == 1 ? shapes.getFirst() : null;
    }

    public static List<Change> compare(Map<String, Object> before, Map<String, Object> after) {
        var changes = new ArrayList<Change>();
        var oldProps = Semantics.map(before.get("properties"));
        var newProps = Semantics.map(after.get("properties"));
        if (!Objects.equals(before.get("stableKey"), after.get("stableKey"))
                || (oldProps.containsKey("symbolName")
                        && newProps.containsKey("symbolName")
                        && !Objects.equals(oldProps.get("symbolName"), newProps.get("symbolName"))))
            changes.add(new Change("RENAMED", "NAME_CHANGED"));
        if (!Objects.equals(oldProps.get("filePath"), newProps.get("filePath")))
            changes.add(new Change("MOVED", "SOURCE_MOVED"));
        if (!Objects.equals(before.get("kind"), after.get("kind"))
                || !Objects.equals(
                        Semantics.canonical(Semantics.behavioral(oldProps)),
                        Semantics.canonical(Semantics.behavioral(newProps)))) {
            String impact = impact(oldProps, newProps);
            changes.add(new Change("MODIFIED", impact));
        }
        if (!Objects.equals(before.get("confidence"), after.get("confidence")))
            changes.add(new Change("CONFIDENCE_CHANGED", "CONFIDENCE_CHANGED"));
        if (!Objects.equals(before.get("evidenceFingerprint"), after.get("evidenceFingerprint")))
            changes.add(new Change("EVIDENCE_CHANGED", "SOURCE_EVIDENCE_CHANGED"));
        return changes;
    }

    public static boolean reviewUnchanged(Map<String, Object> before, Map<String, Object> after) {
        return before.get("fingerprint") != null && Objects.equals(before.get("fingerprint"), after.get("fingerprint"));
    }

    private static String impact(Map<String, Object> before, Map<String, Object> after) {
        var oldCondition = Semantics.map(before.get("normalizedCondition"));
        var newCondition = Semantics.map(after.get("normalizedCondition"));
        if (!collect(oldCondition, "operator").equals(collect(newCondition, "operator"))) return "OPERATOR_CHANGED";
        if (!collectNumbers(oldCondition).equals(collectNumbers(newCondition))) return "THRESHOLD_CHANGED";
        for (var pair : List.of(
                Map.entry("actors", "ACTOR_CHANGED"),
                Map.entry("roles", "ROLE_CHANGED"),
                Map.entry("allowedStatuses", "ALLOWED_STATUS_CHANGED"),
                Map.entry("dataWrites", "DATABASE_WRITE_CHANGED"),
                Map.entry("externalEffects", "EXTERNAL_EFFECT_CHANGED"),
                Map.entry("ownerKey", "OWNERSHIP_CHANGED"),
                Map.entry("trueOutcomes", "OUTCOME_CHANGED"),
                Map.entry("falseOutcomes", "OUTCOME_CHANGED")))
            if (!Objects.equals(before.get(pair.getKey()), after.get(pair.getKey()))) return pair.getValue();
        return "BEHAVIOR_CHANGED";
    }

    private static List<Object> collect(Object value, String key) {
        var result = new ArrayList<Object>();
        if (value instanceof Map<?, ?> map)
            for (var entry : new java.util.TreeMap<>(Semantics.map(map)).entrySet()) {
                if (entry.getKey().equals(key)) result.add(entry.getValue());
                else result.addAll(collect(entry.getValue(), key));
            }
        else if (value instanceof List<?> list) for (Object item : list) result.addAll(collect(item, key));
        return result;
    }

    private static List<Object> collectNumbers(Object value) {
        var result = new ArrayList<Object>();
        if (value instanceof Number number) result.add(number.toString());
        else if (value instanceof Map<?, ?> map)
            for (Object child : new java.util.TreeMap<>(Semantics.map(map)).values())
                result.addAll(collectNumbers(child));
        else if (value instanceof List<?> list) for (Object child : list) result.addAll(collectNumbers(child));
        return result;
    }

    private static String effect(Map<String, Object> node, String suffix) {
        String kind = Objects.toString(node.get("kind"), "SEMANTIC_NODE");
        return kind + "_" + suffix;
    }

    private void save(
            UUID org,
            UUID project,
            UUID from,
            UUID to,
            Map<String, Object> before,
            Map<String, Object> after,
            Change change) {
        var subject = after == null ? before : after;
        double confidence = ((Number) subject.get("confidence")).doubleValue();
        if (before != null) confidence = Math.min(confidence, ((Number) before.get("confidence")).doubleValue());
        db.update(
                "INSERT INTO semantic_change(id,organization_id,project_id,from_analysis_run_id,to_analysis_run_id,change_type,subject_stable_key,impact_type,before_state,after_state,before_evidence_ids,after_evidence_ids,confidence) VALUES (?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(),
                org,
                project,
                from,
                to,
                change.changeType(),
                subject.get("stableKey"),
                change.impactType(),
                db.json(snapshot(before)),
                db.json(snapshot(after)),
                db.json(before == null ? List.of() : before.get("evidenceIds")),
                db.json(after == null ? List.of() : after.get("evidenceIds")),
                confidence);
    }

    private static Object snapshot(Map<String, Object> node) {
        if (node == null) return null;
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("id", "stableKey", "kind", "label", "properties", "confidence", "fingerprint"))
            result.put(key, node.get(key));
        return result;
    }

    private void carryReview(
            UUID org, UUID project, UUID from, UUID to, Map<String, Object> before, Map<String, Object> after) {
        var reviews = db.rows(
                "SELECT * FROM review_decision WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=? ORDER BY created_at DESC,id DESC LIMIT 1",
                org,
                project,
                from,
                before.get("id"));
        if (reviews.isEmpty()
                || !db.rows(
                                "SELECT id FROM review_decision WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=? LIMIT 1",
                                org,
                                project,
                                to,
                                after.get("id"))
                        .isEmpty()) return;
        var review = reviews.getFirst();
        boolean unchanged =
                reviewUnchanged(before, after) && Objects.equals(review.get("fingerprint"), before.get("fingerprint"));
        Object mergeTarget = null;
        if (review.get("mergeTargetId") != null) {
            var targets = db.rows(
                    "SELECT n.id,n.fingerprint,old.fingerprint AS old_fingerprint FROM semantic_node old JOIN semantic_node n ON n.organization_id=old.organization_id AND n.project_id=old.project_id AND n.stable_key=old.stable_key AND n.analysis_run_id=? WHERE old.organization_id=? AND old.project_id=? AND old.analysis_run_id=? AND old.id=?",
                    to,
                    org,
                    project,
                    from,
                    review.get("mergeTargetId"));
            if (targets.isEmpty()
                    || !Objects.equals(
                            targets.getFirst().get("fingerprint"),
                            targets.getFirst().get("oldFingerprint"))) unchanged = false;
            else mergeTarget = targets.getFirst().get("id");
        }
        db.update(
                "INSERT INTO review_decision(id,organization_id,project_id,analysis_run_id,node_id,user_id,decision,comment,edited_title,edited_description,merge_target_id,fingerprint,carried_from_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                UUID.randomUUID(),
                org,
                project,
                to,
                after.get("id"),
                review.get("userId"),
                unchanged ? review.get("decision") : "STALE",
                review.get("comment"),
                review.get("editedTitle"),
                review.get("editedDescription"),
                mergeTarget,
                after.get("fingerprint"),
                review.get("id"));
        if (!unchanged)
            save(org, project, from, to, before, after, new Change("REVIEW_BECAME_STALE", "REVIEW_BECAME_STALE"));
    }

    private void diffEdges(UUID org, UUID project, UUID from, UUID to) {
        // Edge identity uses endpoint stable keys, keeping call/write changes separate from node changes.
        String scoped =
                "SELECT e.*,s.stable_key AS source_key,t.stable_key AS target_key FROM semantic_edge e JOIN semantic_node s ON s.id=e.source_id AND s.organization_id=e.organization_id AND s.project_id=e.project_id AND s.analysis_run_id=e.analysis_run_id JOIN semantic_node t ON t.id=e.target_id AND t.organization_id=e.organization_id AND t.project_id=e.project_id AND t.analysis_run_id=e.analysis_run_id WHERE e.organization_id=? AND e.project_id=? AND e.analysis_run_id=?";
        for (boolean removed : List.of(true, false)) {
            UUID run = removed ? from : to, other = removed ? to : from;
            UUID cursor = new UUID(0, 0);
            while (true) {
                var page = db.rows(scoped + " AND e.id>? ORDER BY e.id LIMIT 500", org, project, run, cursor);
                if (page.isEmpty()) break;
                for (var edge : page) {
                    var counterpart = db.rows(
                            scoped + " AND s.stable_key=? AND t.stable_key=? AND e.kind=? LIMIT 1",
                            org,
                            project,
                            other,
                            edge.get("sourceKey"),
                            edge.get("targetKey"),
                            edge.get("kind"));
                    if (!counterpart.isEmpty()) continue;
                    var node = new LinkedHashMap<>(edge);
                    node.put(
                            "stableKey",
                            "edge:"
                                    + Semantics.hash(
                                            List.of(edge.get("sourceKey"), edge.get("kind"), edge.get("targetKey"))));
                    node.put(
                            "properties",
                            Map.of("sourceKey", edge.get("sourceKey"), "targetKey", edge.get("targetKey")));
                    save(
                            org,
                            project,
                            from,
                            to,
                            removed ? node : null,
                            removed ? null : node,
                            new Change(
                                    removed ? "REMOVED" : "ADDED",
                                    edge.get("kind") + (removed ? "_REMOVED" : "_ADDED")));
                }
                cursor = Semantics.uuid(page.getLast().get("id"));
            }
        }
    }
}
