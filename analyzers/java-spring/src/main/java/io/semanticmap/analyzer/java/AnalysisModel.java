package io.semanticmap.analyzer.java;

import io.semanticmap.contract.Protocol;
import java.util.*;
import org.openrewrite.java.tree.J;

final class AnalysisModel {
    record TypeSymbol(String key, String name, SourceUnit unit, J.ClassDeclaration tree) {}

    record MethodSymbol(
            String key, String name, TypeSymbol owner, J.MethodDeclaration tree, List<String> parameterTypes) {}

    final List<SourceUnit> units = new ArrayList<>();
    final Map<String, TypeSymbol> types = new TreeMap<>();
    final Map<UUID, TypeSymbol> typesById = new HashMap<>();
    final Map<String, MethodSymbol> methods = new TreeMap<>();
    final Map<UUID, MethodSymbol> methodsById = new HashMap<>();
    final Map<String, Object> constants = new HashMap<>();
    final Map<String, Protocol.Evidence> constantEvidence = new HashMap<>();
    final Map<String, String> tables = new HashMap<>();
    final Map<String, String> repositoryEntities = new HashMap<>();
    final Map<String, Protocol.Fact> facts = new TreeMap<>();
    final List<Protocol.Diagnostic> diagnostics = new ArrayList<>();
    int discovered;
    int failed;

    void fact(
            String kind,
            String key,
            String subjectKind,
            String subject,
            String owner,
            String name,
            Map<String, Object> properties,
            SourceUnit unit,
            J node) {
        Map<String, Object> all = new TreeMap<>(properties);
        all.put("name", name);
        all.put("ownerKey", owner);
        String origin =
                switch (kind) {
                    case "PACKAGE", "TYPE", "METHOD", "FIELD", "CONDITION", "FAILURE_BRANCH" -> "STATIC_SYNTAX";
                    case "RELATION" -> "CALLS".equals(properties.get("edgeKind"))
                            ? "STATIC_TYPED"
                            : "FRAMEWORK_DERIVED";
                    default -> "FRAMEWORK_DERIVED";
                };
        List<Protocol.Evidence> evidence = new ArrayList<>();
        evidence.add(unit.evidence(node));
        constantEvidence(properties, evidence);
        facts.put(
                key,
                new Protocol.Fact(
                        Protocol.VERSION,
                        Protocol.factId(key),
                        kind,
                        key,
                        new Protocol.Subject(subjectKind, subject),
                        all,
                        origin,
                        1.0,
                        List.copyOf(evidence)));
    }

    private void constantEvidence(Object value, List<Protocol.Evidence> evidence) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("constantKey") instanceof String key
                    && constantEvidence.containsKey(key)
                    && !evidence.contains(constantEvidence.get(key))) evidence.add(constantEvidence.get(key));
            map.values().forEach(child -> constantEvidence(child, evidence));
        } else if (value instanceof List<?> list) list.forEach(child -> constantEvidence(child, evidence));
    }

    void relation(String source, String target, String edge, SourceUnit unit, J node) {
        String key = "relation:" + edge + ":" + source + "->" + target;
        Protocol.Evidence evidence = unit.evidence(node);
        Protocol.Fact previous = facts.get(key);
        if (previous != null) {
            if (!previous.evidence().contains(evidence)) {
                List<Protocol.Evidence> locations = new ArrayList<>(previous.evidence());
                locations.add(evidence);
                facts.put(
                        key,
                        new Protocol.Fact(
                                previous.contractVersion(),
                                previous.factId(),
                                previous.kind(),
                                key,
                                previous.subject(),
                                previous.properties(),
                                previous.origin(),
                                previous.confidence(),
                                List.copyOf(locations)));
            }
            return;
        }
        fact(
                "RELATION",
                key,
                "RELATION",
                key,
                source,
                edge,
                Map.of("sourceKey", source, "targetKey", target, "edgeKind", edge),
                unit,
                node);
    }

    void diagnostic(String code, String message, SourceUnit unit, J node, Map<String, Object> metadata) {
        diagnostics.add(new Protocol.Diagnostic(
                code,
                "WARNING",
                message,
                unit == null ? null : unit.path,
                unit == null || node == null ? null : unit.evidence(node).startLine(),
                metadata));
    }

    TypeSymbol type(J.ClassDeclaration declaration) {
        return declaration == null ? null : typesById.get(declaration.getId());
    }

    MethodSymbol method(J.MethodDeclaration declaration) {
        return declaration == null ? null : methodsById.get(declaration.getId());
    }
}
