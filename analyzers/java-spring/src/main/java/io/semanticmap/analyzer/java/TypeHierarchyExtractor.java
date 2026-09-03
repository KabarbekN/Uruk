package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.tree.*;

final class TypeHierarchyExtractor extends Extractor {
    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, AnalysisModel model) {
        List<TypeTree> parents = new ArrayList<>();
        if (tree.getExtends() != null) parents.add(tree.getExtends());
        if (tree.getImplements() != null) parents.addAll(tree.getImplements());
        for (TypeTree parent : parents) {
            String key = "java:type:" + Ast.typeName(parent, unit);
            if (model.types.containsKey(key)) model.relation(ownerKey(), key, "EXTENDS", unit, tree);
            if (parent instanceof J.ParameterizedType pt
                    && pt.getTypeParameters() != null
                    && !pt.getTypeParameters().isEmpty()
                    && Ast.typeName(pt.getClazz(), unit).startsWith("org.springframework.data.")
                    && Set.of("JpaRepository", "CrudRepository", "PagingAndSortingRepository", "ListCrudRepository")
                            .contains(Ast.simple(Ast.typeName(pt.getClazz(), unit)))
                    && pt.getTypeParameters().getFirst() instanceof TypeTree entity) {
                model.repositoryEntities.put(ownerKey(), "java:type:" + Ast.typeName(entity, unit));
            }
        }
        return super.visitClassDeclaration(tree, model);
    }
}
