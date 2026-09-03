package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.tree.J;

final class SymbolExtractor extends Extractor {
    @Override
    public J.CompilationUnit visitCompilationUnit(J.CompilationUnit tree, AnalysisModel model) {
        String name = tree.getPackageDeclaration() == null
                ? ""
                : Ast.text(tree.getPackageDeclaration().getExpression());
        String key = "java:package:" + name;
        if (!model.facts.containsKey(key))
            model.fact(
                    "PACKAGE",
                    key,
                    "PACKAGE",
                    key,
                    "",
                    name,
                    Map.of(),
                    unit,
                    tree.getPackageDeclaration() == null ? tree : tree.getPackageDeclaration());
        return super.visitCompilationUnit(tree, model);
    }

    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, AnalysisModel model) {
        String pkg = unit.tree.getPackageDeclaration() == null
                ? ""
                : Ast.text(unit.tree.getPackageDeclaration().getExpression()) + ".";
        AnalysisModel.TypeSymbol enclosing =
                model.type(getCursor().getParentTreeCursor().firstEnclosing(J.ClassDeclaration.class));
        String name = enclosing == null ? pkg + tree.getSimpleName() : enclosing.name() + "$" + tree.getSimpleName();
        String key = "java:type:" + name;
        var symbol = new AnalysisModel.TypeSymbol(key, name, unit, tree);
        model.types.put(key, symbol);
        model.typesById.put(tree.getId(), symbol);
        model.fact(
                "TYPE",
                key,
                "TYPE",
                key,
                enclosing == null ? "java:package:" + pkg.replaceAll("\\.$", "") : enclosing.key(),
                tree.getSimpleName(),
                Map.of("qualifiedName", name, "typeKind", tree.getKind().name(), "testSource", unit.test()),
                unit,
                tree);
        return super.visitClassDeclaration(tree, model);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        if (owner() == null) return super.visitMethodDeclaration(tree, model);
        List<String> params = Ast.parameters(tree).stream()
                .map(p -> Ast.typeName(p.getTypeExpression(), unit))
                .toList();
        String key =
                "java:method:" + owner().name() + "#" + tree.getSimpleName() + "(" + String.join(",", params) + ")";
        var symbol = new AnalysisModel.MethodSymbol(key, tree.getSimpleName(), owner(), tree, params);
        model.methods.put(key, symbol);
        model.methodsById.put(tree.getId(), symbol);
        model.fact(
                "METHOD",
                key,
                "METHOD",
                key,
                ownerKey(),
                tree.getSimpleName(),
                Map.of(
                        "parameterTypes",
                        params,
                        "returnType",
                        Ast.typeName(tree.getReturnTypeExpression(), unit),
                        "constructor",
                        tree.isConstructor(),
                        "testSource",
                        unit.test()),
                unit,
                tree);
        model.relation(ownerKey(), key, "CONTAINS", unit, tree);
        return super.visitMethodDeclaration(tree, model);
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations tree, AnalysisModel model) {
        if (owner() != null && method() == null)
            for (var variable : tree.getVariables()) {
                String key = ownerKey() + "#" + variable.getSimpleName();
                model.fact(
                        "FIELD",
                        key,
                        "FIELD",
                        key,
                        ownerKey(),
                        variable.getSimpleName(),
                        Map.of("type", Ast.typeName(tree.getTypeExpression(), unit)),
                        unit,
                        tree);
                model.relation(ownerKey(), key, "CONTAINS", unit, tree);
            }
        return super.visitVariableDeclarations(tree, model);
    }
}
