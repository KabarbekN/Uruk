package io.semanticmap.analyzer.java;

import java.util.Map;
import org.openrewrite.java.tree.J;

final class ConstantResolver extends Extractor {
    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations tree, AnalysisModel model) {
        if (owner() != null
                && method() == null
                && tree.hasModifier(J.Modifier.Type.Static)
                && tree.hasModifier(J.Modifier.Type.Final)) {
            for (var variable : tree.getVariables())
                if (variable.getInitializer() != null) {
                    Object value = Ast.value(variable.getInitializer(), model, ownerKey());
                    if (!(value instanceof Map)) {
                        String key = ownerKey() + "#" + variable.getSimpleName();
                        model.constants.put(key, value);
                        model.constantEvidence.put(key, unit.evidence(tree));
                    }
                }
        }
        return super.visitVariableDeclarations(tree, model);
    }
}
