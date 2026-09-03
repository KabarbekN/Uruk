package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;

final class ConditionIR {
    private ConditionIR() {}

    static Map<String, Object> expression(Expression expression, AnalysisModel model, String owner) {
        if (expression == null) return Map.of("type", "EMPTY");
        if (expression instanceof J.Parentheses<?> p && p.getTree() instanceof Expression e)
            return expression(e, model, owner);
        if (expression instanceof J.ControlParentheses<?> p && p.getTree() instanceof Expression e)
            return expression(e, model, owner);
        if (expression instanceof J.Binary binary)
            return Map.of(
                    "type",
                    "BINARY",
                    "operator",
                    operator(binary.getOperator().name()),
                    "left",
                    expression(binary.getLeft(), model, owner),
                    "right",
                    expression(binary.getRight(), model, owner));
        if (expression instanceof J.Unary unary)
            return Map.of(
                    "type",
                    "UNARY",
                    "operator",
                    operator(unary.getOperator().name()),
                    "operand",
                    expression(unary.getExpression(), model, owner));
        if (expression instanceof J.Literal literal) {
            Map<String, Object> result = new TreeMap<>();
            result.put("type", "LITERAL");
            result.put("value", literal.getValue());
            return result;
        }
        if (expression instanceof J.Identifier || expression instanceof J.FieldAccess) {
            Object value = Ast.value(expression, model, owner);
            if (!(value instanceof Map))
                return Map.of(
                        "type",
                        "CONSTANT",
                        "name",
                        Ast.text(expression),
                        "value",
                        value,
                        "constantKey",
                        Ast.constantKey(expression, model, owner));
            if (expression instanceof J.FieldAccess field)
                return Map.of(
                        "type",
                        "MEMBER",
                        "name",
                        field.getSimpleName(),
                        "target",
                        expression(field.getTarget(), model, owner));
            return Map.of("type", "REFERENCE", "name", Ast.text(expression));
        }
        if (expression instanceof J.MethodInvocation call)
            return Map.of(
                    "type",
                    "CALL",
                    "method",
                    call.getSimpleName(),
                    "receiver",
                    call.getSelect() == null ? Map.of("type", "THIS") : expression(call.getSelect(), model, owner),
                    "arguments",
                    call.getArguments().stream()
                            .filter(e -> !(e instanceof J.Empty))
                            .map(e -> expression(e, model, owner))
                            .toList());
        if (expression instanceof J.Ternary t)
            return Map.of(
                    "type",
                    "TERNARY",
                    "condition",
                    expression(t.getCondition(), model, owner),
                    "whenTrue",
                    expression(t.getTruePart(), model, owner),
                    "whenFalse",
                    expression(t.getFalsePart(), model, owner));
        if (expression instanceof J.NewClass n)
            return Map.of(
                    "type",
                    "CONSTRUCT",
                    "class",
                    Ast.text(n.getClazz()),
                    "arguments",
                    n.getArguments().stream()
                            .filter(e -> !(e instanceof J.Empty))
                            .map(e -> expression(e, model, owner))
                            .toList());
        if (expression instanceof J.TypeCast cast)
            return Map.of(
                    "type",
                    "CAST",
                    "targetType",
                    Ast.text(cast.getClazz()),
                    "value",
                    expression(cast.getExpression(), model, owner));
        return Map.of("type", "UNKNOWN_EXPRESSION", "source", Ast.text(expression));
    }

    private static String operator(String name) {
        return name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    static List<Map<String, Object>> outcomes(J branch, AnalysisModel model, String owner) {
        if (branch == null) return List.of(Map.of("kind", "CONTINUE"));
        List<Map<String, Object>> result = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda tree, Integer p) {
                return tree;
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, Integer p) {
                return tree;
            }

            @Override
            public J.If visitIf(J.If tree, Integer p) {
                result.add(Map.of(
                        "kind",
                        "CONDITIONAL",
                        "condition",
                        expression(tree.getIfCondition().getTree(), model, owner),
                        "trueOutcomes",
                        outcomes(tree.getThenPart(), model, owner),
                        "falseOutcomes",
                        outcomes(
                                tree.getElsePart() == null
                                        ? null
                                        : tree.getElsePart().getBody(),
                                model,
                                owner)));
                return tree;
            }

            @Override
            public J.Throw visitThrow(J.Throw tree, Integer p) {
                result.add(Map.of(
                        "kind",
                        "THROW",
                        "exception",
                        tree.getException() instanceof J.NewClass n
                                ? Ast.text(n.getClazz())
                                : Ast.text(tree.getException()),
                        "expression",
                        expression(tree.getException(), model, owner)));
                return tree;
            }

            @Override
            public J.Return visitReturn(J.Return tree, Integer p) {
                result.add(Map.of("kind", "RETURN", "expression", expression(tree.getExpression(), model, owner)));
                return super.visitReturn(tree, p);
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment tree, Integer p) {
                result.add(Map.of(
                        "kind",
                        "WRITE",
                        "target",
                        Ast.text(tree.getVariable()),
                        "value",
                        expression(tree.getAssignment(), model, owner)));
                return super.visitAssignment(tree, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation tree, Integer p) {
                result.add(Map.of(
                        "kind",
                        "WRITE",
                        "target",
                        Ast.text(tree.getVariable()),
                        "operator",
                        tree.getOperator().name(),
                        "value",
                        expression(tree.getAssignment(), model, owner)));
                return super.visitAssignmentOperation(tree, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation tree, Integer p) {
                String receiver = MethodCallExtractor.receiverType(tree, model, owner);
                String kind = ExternalCallExtractor.event(receiver, tree)
                        ? "EVENT_PUBLICATION"
                        : ExternalCallExtractor.external(receiver, tree, model)
                                ? "EXTERNAL_CALL"
                                : model.repositoryEntities.containsKey("java:type:" + receiver)
                                                && MethodCallExtractor.write(tree.getSimpleName())
                                        ? "DATABASE_WRITE"
                                        : "CALL";
                result.add(
                        Map.of("kind", kind, "method", Ast.text(tree), "expression", expression(tree, model, owner)));
                return super.visitMethodInvocation(tree, p);
            }
        }.visit(branch, 0);
        if (result.isEmpty() && branch instanceof Expression expression)
            result.add(Map.of("kind", "VALUE", "expression", expression(expression, model, owner)));
        return result.isEmpty() ? List.of(Map.of("kind", "CONTINUE")) : result;
    }
}

final class NormalizedConditionExtractor extends Extractor {
    private final Map<String, Integer> slots = new HashMap<>();

    private String key(String kind) {
        String owner = method().key() + ":" + kind;
        // Structural preorder within a method is independent of literals, source lines, and UUIDs.
        return "rule:" + owner + ":" + (slots.merge(owner, 1, Integer::sum) - 1);
    }

    private void condition(String kind, Expression condition, J yes, J no, J source) {
        if (method() == null) return;
        String key = key(kind);
        Map<String, Object> props = new TreeMap<>();
        props.put("condition", ConditionIR.expression(condition, model, ownerKey()));
        props.put("trueOutcomes", ConditionIR.outcomes(yes, model, ownerKey()));
        props.put("falseOutcomes", ConditionIR.outcomes(no, model, ownerKey()));
        props.put("structuralSlot", key.substring(key.lastIndexOf(':') + 1));
        props.put("conditionKind", kind);
        props.put("sourceExpression", Ast.text(condition));
        model.fact(
                "CONDITION",
                key,
                "METHOD",
                method().key(),
                method().key(),
                kind + " " + Ast.text(condition),
                props,
                unit,
                source);
        model.relation(method().key(), key, "GUARDED_BY", unit, source);
    }

    @Override
    public J.If visitIf(J.If tree, AnalysisModel model) {
        condition(
                "if",
                tree.getIfCondition().getTree(),
                tree.getThenPart(),
                tree.getElsePart() == null ? null : tree.getElsePart().getBody(),
                tree);
        return super.visitIf(tree, model);
    }

    @Override
    public J.Ternary visitTernary(J.Ternary tree, AnalysisModel model) {
        condition("ternary", tree.getCondition(), tree.getTruePart(), tree.getFalsePart(), tree);
        return super.visitTernary(tree, model);
    }

    private void switchCases(Expression selector, J.Block cases, J source) {
        if (method() == null) return;
        String key = key("switch");
        List<Object> branches = new ArrayList<>();
        for (Statement statement : cases.getStatements())
            if (statement instanceof J.Case c) {
                List<Object> outcomes = new ArrayList<>();
                if (c.getBody() != null) outcomes.addAll(ConditionIR.outcomes(c.getBody(), model, ownerKey()));
                for (Statement child : c.getStatements())
                    outcomes.addAll(ConditionIR.outcomes(child, model, ownerKey()));
                branches.add(Map.of(
                        "labels",
                        c.getCaseLabels().stream().map(Ast::text).toList(),
                        "outcomes",
                        outcomes,
                        "fallThroughPossible",
                        c.getType() == J.Case.Type.Statement));
            }
        model.fact(
                "CONDITION",
                key,
                "METHOD",
                method().key(),
                method().key(),
                "switch " + Ast.text(selector),
                Map.of(
                        "condition",
                        ConditionIR.expression(selector, model, ownerKey()),
                        "conditionKind",
                        "switch",
                        "branches",
                        branches,
                        "trueOutcomes",
                        List.of(),
                        "falseOutcomes",
                        List.of()),
                unit,
                source);
        model.relation(method().key(), key, "GUARDED_BY", unit, source);
    }

    @Override
    public J.Switch visitSwitch(J.Switch tree, AnalysisModel model) {
        switchCases(tree.getSelector().getTree(), tree.getCases(), tree);
        return super.visitSwitch(tree, model);
    }

    @Override
    public J.SwitchExpression visitSwitchExpression(J.SwitchExpression tree, AnalysisModel model) {
        switchCases(tree.getSelector().getTree(), tree.getCases(), tree);
        return super.visitSwitchExpression(tree, model);
    }
}

final class ExceptionFlowExtractor extends Extractor {
    private final Map<String, Integer> slots = new HashMap<>();

    @Override
    public J.Throw visitThrow(J.Throw tree, AnalysisModel model) {
        if (method() != null) {
            String key = "failure:" + method().key() + ":" + slots.merge(method().key(), 1, Integer::sum);
            model.fact(
                    "FAILURE_BRANCH",
                    key,
                    "METHOD",
                    method().key(),
                    method().key(),
                    "throw " + Ast.text(tree.getException()),
                    Map.of(
                            "exception",
                            ConditionIR.expression(tree.getException(), model, ownerKey()),
                            "catchResolution",
                            "NOT_ANALYZED"),
                    unit,
                    tree);
            model.relation(method().key(), key, "THROWS", unit, tree);
        }
        return super.visitThrow(tree, model);
    }
}
