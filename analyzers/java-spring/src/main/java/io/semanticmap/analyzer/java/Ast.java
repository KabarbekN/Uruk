package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.tree.*;

final class Ast {
    private Ast() {}

    static String text(J tree) {
        return tree == null ? "" : tree.printTrimmed();
    }

    static String simple(String name) {
        return name.substring(Math.max(name.lastIndexOf('.'), name.lastIndexOf('$')) + 1);
    }

    static List<J.VariableDeclarations> parameters(J.MethodDeclaration method) {
        return method.getParameters().stream()
                .filter(J.VariableDeclarations.class::isInstance)
                .map(J.VariableDeclarations.class::cast)
                .toList();
    }

    static String qualified(String name, SourceUnit unit) {
        if (name.contains(".")
                || Set.of("boolean", "byte", "short", "int", "long", "float", "double", "char", "void")
                        .contains(name)) return name;
        for (J.Import imp : unit.tree.getImports()) {
            if (!imp.isStatic() && imp.getQualid().getSimpleName().equals(name)) return text(imp.getQualid());
        }
        if (Set.of("String", "Long", "Integer", "Boolean", "Object", "Double", "RuntimeException")
                .contains(name)) return "java.lang." + name;
        return (unit.tree.getPackageDeclaration() == null
                        ? ""
                        : text(unit.tree.getPackageDeclaration().getExpression()) + ".")
                + name;
    }

    static String typeName(J tree, SourceUnit unit) {
        if (tree == null) return "void";
        JavaType.FullyQualified fq =
                tree instanceof TypedTree typed ? TypeUtils.asFullyQualified(typed.getType()) : null;
        if (fq != null && !(fq instanceof JavaType.Unknown)) return fq.getFullyQualifiedName();
        if (tree instanceof J.ParameterizedType pt) return typeName(pt.getClazz(), unit);
        if (tree instanceof J.ArrayType a) return typeName(a.getElementType(), unit) + "[]";
        return qualified(text(tree), unit);
    }

    static String annotationName(J.Annotation annotation, SourceUnit unit) {
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(annotation.getType());
        if (fq != null && !(fq instanceof JavaType.Unknown)) return fq.getFullyQualifiedName();
        String n = text(annotation.getAnnotationType());
        String qualified = qualified(n, unit);
        if (!qualified.endsWith("." + n) || qualified.startsWith("org.") || qualified.startsWith("jakarta."))
            return qualified;
        for (J.Import imp : unit.tree.getImports()) {
            if (!imp.isStatic() && imp.getQualid().getSimpleName().equals("*")) {
                String pkg = text(imp.getQualid()).replace(".*", "");
                if (pkg.startsWith("org.springframework")
                        || pkg.startsWith("jakarta.")
                        || pkg.startsWith("org.junit")
                        || pkg.startsWith("org.hibernate.annotations")) return pkg + "." + n;
            }
        }
        return qualified;
    }

    static boolean is(J.Annotation annotation, SourceUnit unit, String... names) {
        String fq = annotationName(annotation, unit);
        return Arrays.stream(names)
                .anyMatch(n -> n.contains(".")
                        ? fq.equals(n)
                        : simple(fq).equals(n)
                                && (fq.startsWith("org.springframework.")
                                        || fq.startsWith("jakarta.")
                                        || fq.startsWith("org.hibernate.")
                                        || fq.startsWith("org.junit.")));
    }

    static Optional<J.Annotation> annotation(List<J.Annotation> annotations, SourceUnit unit, String... names) {
        return annotations.stream().filter(a -> is(a, unit, names)).findFirst();
    }

    static Map<String, Object> arguments(J.Annotation annotation, AnalysisModel model, String owner) {
        Map<String, Object> args = new TreeMap<>();
        if (annotation.getArguments() != null)
            for (Expression expression : annotation.getArguments()) {
                if (expression instanceof J.Assignment assignment)
                    args.put(text(assignment.getVariable()), value(assignment.getAssignment(), model, owner));
                else if (!(expression instanceof J.Empty)) args.put("value", value(expression, model, owner));
            }
        return args;
    }

    static Object value(Expression expression, AnalysisModel model, String owner) {
        if (expression instanceof J.Literal literal) return literal.getValue() == null ? "null" : literal.getValue();
        if (expression instanceof J.NewArray array && array.getInitializer() != null)
            return array.getInitializer().stream()
                    .map(e -> value(e, model, owner))
                    .toList();
        if (expression instanceof J.Unary unary
                && unary.getOperator() == J.Unary.Type.Negative
                && value(unary.getExpression(), model, owner) instanceof Number n) return -n.doubleValue();
        if (expression instanceof J.Identifier id
                && id.getFieldType() != null
                && id.getFieldType().getOwner() instanceof JavaType.Method)
            return Map.of("expression", text(expression), "resolved", false);
        String name = text(expression);
        Object constant = model.constants.get(owner + "#" + name);
        if (constant != null) return constant;
        if (expression instanceof J.Identifier id && id.getFieldType() != null) {
            JavaType.FullyQualified fq =
                    TypeUtils.asFullyQualified(id.getFieldType().getOwner());
            if (fq != null) {
                constant = model.constants.get("java:type:" + fq.getFullyQualifiedName() + "#" + id.getSimpleName());
                if (constant != null) return constant;
            }
        }
        if (expression instanceof J.FieldAccess field) {
            String fieldOwner = "java:type:"
                    + qualified(
                            text(field.getTarget()),
                            model.types.get(owner) == null
                                    ? model.units.getFirst()
                                    : model.types.get(owner).unit());
            constant = model.constants.get(fieldOwner + "#" + field.getSimpleName());
            if (constant != null) return constant;
        }
        return Map.of("expression", name, "resolved", false);
    }

    static String constantKey(Expression expression, AnalysisModel model, String owner) {
        if (expression instanceof J.Identifier id && id.getFieldType() != null) {
            JavaType.FullyQualified fq =
                    TypeUtils.asFullyQualified(id.getFieldType().getOwner());
            if (fq != null) {
                String key = "java:type:" + fq.getFullyQualifiedName() + "#" + id.getSimpleName();
                if (model.constants.containsKey(key)) return key;
            }
        }
        if (expression instanceof J.FieldAccess field && model.types.containsKey(owner)) {
            String key = "java:type:"
                    + qualified(text(field.getTarget()), model.types.get(owner).unit()) + "#" + field.getSimpleName();
            if (model.constants.containsKey(key)) return key;
        }
        return owner + "#" + text(expression);
    }

    static List<String> strings(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list)
            return list.stream().flatMap(v -> strings(v).stream()).toList();
        return value instanceof String s ? List.of(s) : List.of();
    }

    static String argumentText(J.Annotation annotation, String key) {
        if (annotation.getArguments() != null)
            for (Expression e : annotation.getArguments()) {
                if (e instanceof J.Assignment a && text(a.getVariable()).equals(key)) return text(a.getAssignment());
                if (key.equals("value") && !(e instanceof J.Assignment)) return text(e);
            }
        return "";
    }
}
