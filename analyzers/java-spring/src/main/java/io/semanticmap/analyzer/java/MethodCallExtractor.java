package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

class MethodCallExtractor extends Extractor {
    private final Map<String, Integer> slots = new HashMap<>();

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation tree, AnalysisModel model) {
        if (method() == null) return super.visitMethodInvocation(tree, model);
        String receiver = receiverType(tree, model, ownerKey());
        var target = resolve(tree, model, ownerKey());
        if (target != null) model.relation(method().key(), target.key(), "CALLS", unit, tree);
        else
            model.diagnostic(
                    "UNRESOLVED_METHOD_TARGET",
                    "Cannot resolve source target of call " + Ast.text(tree),
                    unit,
                    tree,
                    Map.of("symbol", tree.getSimpleName(), "receiverType", receiver, "callerKey", method().key()));
        String entity = model.repositoryEntities.get("java:type:" + receiver);
        if (entity != null && !unit.test()) {
            boolean write = write(tree.getSimpleName());
            boolean read = tree.getSimpleName().matches("(find|read|get|query|search|stream|count|exists).*");
            if (write || read) {
                String key = "orm:operation:" + method().key() + ":" + slots.merge(method().key(), 1, Integer::sum);
                Map<String, Object> props = new TreeMap<>();
                props.put("operation", tree.getSimpleName());
                props.put("entityKey", entity);
                props.put("repositoryKey", "java:type:" + receiver);
                props.put("semantics", "ORM");
                props.put("exactSql", false);
                props.put("sqlExecutionTime", "PROVIDER_AND_TRANSACTION_DEPENDENT");
                props.put(
                        "arguments",
                        tree.getArguments().stream()
                                .map(e -> ConditionIR.expression(e, model, ownerKey()))
                                .toList());
                String table = model.tables.get(entity);
                if (table != null) props.put("tableKey", table);
                model.fact(
                        write ? "DATA_WRITE" : "DATA_READ",
                        key,
                        "METHOD",
                        method().key(),
                        method().key(),
                        tree.getSimpleName(),
                        props,
                        unit,
                        tree);
                model.relation(method().key(), key, write ? "WRITES" : "READS", unit, tree);
                if (table != null) model.relation(key, table, write ? "WRITES" : "READS", unit, tree);
            }
        }
        return super.visitMethodInvocation(tree, model);
    }

    static boolean write(String method) {
        return method.matches("(save|delete|remove|flush|insert|update).*");
    }

    static String receiverType(J.MethodInvocation call, AnalysisModel model, String owner) {
        if (call.getSelect() == null || Ast.text(call.getSelect()).equals("this")) {
            if (call.getMethodType() != null) {
                String fq = call.getMethodType().getDeclaringType().getFullyQualifiedName();
                if (model.types.containsKey("java:type:" + fq)) return fq;
            }
            return owner.replace("java:type:", "");
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(call.getSelect().getType());
        if (fq != null && !(fq instanceof JavaType.Unknown)) return fq.getFullyQualifiedName();
        String name = Ast.text(call.getSelect()).replaceFirst("^this\\.", "");
        var type = model.types.get(owner);
        if (type == null) return "";
        // A declared receiver type is useful without external dependencies. Ambiguity is retained.
        Set<String> declarations = new HashSet<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations tree, Integer p) {
                if (tree.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(name)))
                    declarations.add(Ast.typeName(tree.getTypeExpression(), type.unit()));
                return super.visitVariableDeclarations(tree, p);
            }
        }.visit(type.tree(), 0);
        if (declarations.size() == 1) return declarations.iterator().next();
        String qualified = Ast.qualified(name, type.unit());
        return model.types.containsKey("java:type:" + qualified) ? qualified : "";
    }

    static AnalysisModel.MethodSymbol resolve(J.MethodInvocation call, AnalysisModel model, String owner) {
        String receiver = receiverType(call, model, owner);
        int arity = (int) call.getArguments().stream()
                .filter(a -> !(a instanceof J.Empty))
                .count();
        List<AnalysisModel.MethodSymbol> candidates = model.methods.values().stream()
                .filter(m -> m.owner().name().equals(receiver)
                        && m.name().equals(call.getSimpleName())
                        && m.parameterTypes().size() == arity)
                .toList();
        if (candidates.size() == 1) {
            var candidate = candidates.getFirst();
            List<Expression> arguments = call.getArguments().stream()
                    .filter(a -> !(a instanceof J.Empty))
                    .toList();
            for (int i = 0; i < arguments.size(); i++) {
                JavaType type = arguments.get(i).getType();
                if (type != null
                        && !(type instanceof JavaType.Unknown)
                        && !(arguments.get(i) instanceof J.Literal l && l.getValue() == null)
                        && !TypeUtils.isAssignableTo(candidate.parameterTypes().get(i), type)) return null;
            }
            return candidate;
        }
        if (call.getArguments().stream()
                .anyMatch(a -> a.getType() == null
                        || a.getType() instanceof JavaType.Unknown
                        || a instanceof J.Literal l && l.getValue() == null)) return null;
        if (call.getMethodType() != null && candidates.size() > 1) {
            List<String> params = call.getMethodType().getParameterTypes().stream()
                    .map(t -> t instanceof JavaType.FullyQualified fq ? fq.getFullyQualifiedName() : t.toString())
                    .toList();
            List<AnalysisModel.MethodSymbol> exact = candidates.stream()
                    .filter(m -> m.parameterTypes().equals(params))
                    .toList();
            if (exact.size() == 1) return exact.getFirst();
        }
        return null;
    }
}

final class ExternalCallExtractor extends Extractor {
    private final Map<String, Integer> slots = new HashMap<>();

    static boolean event(String receiver, J.MethodInvocation call) {
        return receiver.equals("org.springframework.context.ApplicationEventPublisher")
                && call.getSimpleName().equals("publishEvent");
    }

    static boolean external(String receiver, J.MethodInvocation call, AnalysisModel model) {
        var target = model.types.get("java:type:" + receiver);
        if (target != null
                && Ast.annotation(target.tree().getLeadingAnnotations(), target.unit(), "FeignClient")
                        .isPresent()) return true;
        return receiver.equals("org.springframework.web.client.RestTemplate")
                        && call.getSimpleName().matches("(get|post|put|delete|patch|exchange|execute|head|options).*")
                || receiver.startsWith("org.springframework.web.reactive.function.client.WebClient")
                        && Set.of("retrieve", "exchange", "exchangeToMono", "exchangeToFlux")
                                .contains(call.getSimpleName());
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation tree, AnalysisModel model) {
        if (method() == null || unit.test()) return super.visitMethodInvocation(tree, model);
        String receiver = MethodCallExtractor.receiverType(tree, model, ownerKey());
        boolean event = event(receiver, tree), external = external(receiver, tree, model);
        if (event || external) {
            String key = "effect:" + method().key() + ":" + slots.merge(method().key(), 1, Integer::sum);
            Map<String, Object> props = new TreeMap<>();
            props.put("effectKind", event ? "EVENT_PUBLICATION" : "EXTERNAL_CALL");
            props.put("receiverType", receiver);
            props.put("method", tree.getSimpleName());
            props.put(
                    "arguments",
                    tree.getArguments().stream()
                            .map(e -> ConditionIR.expression(e, model, ownerKey()))
                            .toList());
            var target = model.types.get("java:type:" + receiver);
            if (target != null)
                Ast.annotation(target.tree().getLeadingAnnotations(), target.unit(), "FeignClient")
                        .ifPresent(annotation -> props.put("client", Ast.arguments(annotation, model, target.key())));
            model.fact(
                    "SIDE_EFFECT",
                    key,
                    "METHOD",
                    method().key(),
                    method().key(),
                    event ? "Publish event" : "External " + tree.getSimpleName(),
                    props,
                    unit,
                    tree);
            model.relation(method().key(), key, event ? "EMITS" : "CALLS_EXTERNAL", unit, tree);
        }
        return super.visitMethodInvocation(tree, model);
    }
}

final class TestLinkExtractor extends Extractor {
    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        if (isTest(tree)) {
            String key = "test:" + model.method(tree).key();
            Map<String, Object> props = new TreeMap<>();
            props.put("evidenceLevel", "TEST_SOURCE");
            props.put("executed", false);
            Ast.annotation(tree.getLeadingAnnotations(), unit, "DisplayName")
                    .ifPresent(a -> props.put(
                            "displayName", Ast.arguments(a, model, ownerKey()).getOrDefault("value", "")));
            model.fact(
                    "TEST",
                    key,
                    "METHOD",
                    model.method(tree).key(),
                    ownerKey(),
                    tree.getSimpleName(),
                    props,
                    unit,
                    tree);
        }
        return super.visitMethodDeclaration(tree, model);
    }

    private boolean isTest(J.MethodDeclaration tree) {
        return Ast.annotation(
                        tree.getLeadingAnnotations(), unit, "Test", "ParameterizedTest", "RepeatedTest", "TestFactory")
                .isPresent();
    }

    @Override
    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation tree, AnalysisModel model) {
        if (method() != null && isTest(method().tree())) {
            var target = MethodCallExtractor.resolve(tree, model, ownerKey());
            if (target != null && !target.owner().unit().test())
                model.relation(target.key(), "test:" + method().key(), "VERIFIED_BY", unit, tree);
        }
        return super.visitMethodInvocation(tree, model);
    }
}
