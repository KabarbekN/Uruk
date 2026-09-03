package io.semanticmap.analyzer.java;

import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

abstract class Extractor extends JavaIsoVisitor<AnalysisModel> {
    SourceUnit unit;
    AnalysisModel model;

    void extract(AnalysisModel model) {
        this.model = model;
        for (SourceUnit source : model.units) {
            unit = source;
            visit(source.tree, model);
        }
    }

    AnalysisModel.TypeSymbol owner() {
        return model.type(getCursor().firstEnclosing(J.ClassDeclaration.class));
    }

    AnalysisModel.MethodSymbol method() {
        return model.method(getCursor().firstEnclosing(J.MethodDeclaration.class));
    }

    String ownerKey() {
        return owner() == null ? "java:package:" : owner().key();
    }
}
