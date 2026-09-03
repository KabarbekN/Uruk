package io.semanticmap.analyzer.java;

import java.util.*;
import org.openrewrite.java.tree.*;
import org.springframework.expression.spel.SpelNode;
import org.springframework.expression.spel.ast.Literal;
import org.springframework.expression.spel.ast.MethodReference;
import org.springframework.expression.spel.standard.SpelExpressionParser;

final class SpringEndpointExtractor extends Extractor {
    static final String[] MAPPINGS = {
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping"
    };

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        if (owner() == null
                || Ast.annotation(owner().tree().getLeadingAnnotations(), unit, "RestController", "Controller")
                        .isEmpty()) return super.visitMethodDeclaration(tree, model);
        Optional<J.Annotation> mapping = Ast.annotation(tree.getLeadingAnnotations(), unit, MAPPINGS);
        if (mapping.isEmpty()) return super.visitMethodDeclaration(tree, model);
        var annotation = mapping.get();
        var args = Ast.arguments(annotation, model, ownerKey());
        var classMapping = Ast.annotation(owner().tree().getLeadingAnnotations(), unit, "RequestMapping");
        var classArgs =
                classMapping.map(a -> Ast.arguments(a, model, ownerKey())).orElse(Map.of());
        List<String> prefixes = paths(classArgs), suffixes = paths(args);
        if (prefixes.isEmpty() || suffixes.isEmpty()) {
            model.diagnostic(
                    "UNRESOLVED_ENDPOINT_PATH",
                    "Mapping path is not a literal or resolved source constant",
                    unit,
                    annotation,
                    Map.of());
            return super.visitMethodDeclaration(tree, model);
        }
        List<String> verbs = verbs(annotation);
        if (verbs.equals(List.of("ANY")) && classMapping.isPresent()) verbs = verbs(classMapping.get());
        var current = model.method(tree);
        for (String prefix : prefixes)
            for (String suffix : suffixes)
                for (String verb : verbs) {
                    String path = suffix.isEmpty()
                            ? prefix
                            : prefix.isEmpty()
                                    ? suffix
                                    : prefix.endsWith("/") && suffix.startsWith("/")
                                            ? prefix + suffix.substring(1)
                                            : prefix.endsWith("/") || suffix.startsWith("/")
                                                    ? prefix + suffix
                                                    : prefix + "/" + suffix;
                    if (!path.startsWith("/")) path = "/" + path;
                    if (path.isEmpty()) path = "/";
                    String key = "spring:endpoint:" + verb + ":" + path;
                    Map<String, Object> props = new TreeMap<>();
                    props.put("httpMethod", verb);
                    props.put("path", path);
                    props.put("controllerMethodKey", current.key());
                    props.put("responseType", Ast.typeName(tree.getReturnTypeExpression(), unit));
                    props.put("consumes", args.getOrDefault("consumes", classArgs.getOrDefault("consumes", List.of())));
                    props.put("produces", args.getOrDefault("produces", classArgs.getOrDefault("produces", List.of())));
                    List<Object> parameters = new ArrayList<>();
                    for (var parameter : Ast.parameters(tree)) {
                        for (var pa : parameter.getLeadingAnnotations())
                            if (Ast.is(pa, unit, "RequestBody", "PathVariable", "RequestParam", "RequestHeader")) {
                                String kind = pa.getSimpleName();
                                parameters.add(Map.of(
                                        "kind",
                                        kind,
                                        "name",
                                        parameter.getVariables().getFirst().getSimpleName(),
                                        "type",
                                        Ast.typeName(parameter.getTypeExpression(), unit),
                                        "attributes",
                                        Ast.arguments(pa, model, ownerKey())));
                                if (kind.equals("RequestBody"))
                                    props.put("requestBodyType", Ast.typeName(parameter.getTypeExpression(), unit));
                            }
                    }
                    props.put("parameters", parameters);
                    Ast.annotation(tree.getLeadingAnnotations(), unit, "ResponseStatus")
                            .ifPresent(a -> props.put(
                                    "declaredStatus",
                                    Ast.argumentText(a, "value").isEmpty()
                                            ? Ast.argumentText(a, "code")
                                            : Ast.argumentText(a, "value")));
                    props.put(
                            "securityAnnotations",
                            tree.getLeadingAnnotations().stream()
                                    .filter(a ->
                                            Ast.is(a, unit, "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed"))
                                    .map(Ast::text)
                                    .toList());
                    if (model.facts.containsKey(key))
                        model.diagnostic(
                                "DUPLICATE_ENDPOINT_MAPPING",
                                "More than one controller declares this mapping",
                                unit,
                                annotation,
                                Map.of("endpointKey", key));
                    else
                        model.fact(
                                "ENDPOINT",
                                key,
                                "METHOD",
                                current.key(),
                                ownerKey(),
                                verb + " " + path,
                                props,
                                unit,
                                tree);
                    model.relation(key, current.key(), "ENTRY_TO", unit, tree);
                    for (var parameter : Ast.parameters(tree)) {
                        String dto = "java:type:" + Ast.typeName(parameter.getTypeExpression(), unit);
                        if (model.types.containsKey(dto)
                                && Ast.annotation(parameter.getLeadingAnnotations(), unit, "Valid", "Validated")
                                        .isPresent()) model.relation(key, dto, "VALIDATES", unit, parameter);
                    }
                }
        return super.visitMethodDeclaration(tree, model);
    }

    static List<String> paths(Map<String, Object> args) {
        if (!args.containsKey("path") && !args.containsKey("value")) return List.of("");
        return Ast.strings(args.getOrDefault("path", args.get("value")));
    }

    static List<String> verbs(J.Annotation annotation) {
        String name = annotation.getSimpleName();
        if (!name.equals("RequestMapping"))
            return List.of(name.replace("Mapping", "").toUpperCase(Locale.ROOT));
        List<String> result = new ArrayList<>();
        if (annotation.getArguments() != null)
            for (Expression argument : annotation.getArguments()) {
                if (argument instanceof J.Assignment a
                        && Ast.text(a.getVariable()).equals("method")) enumValues(a.getAssignment(), result);
            }
        return result.isEmpty() ? List.of("ANY") : result;
    }

    static void enumValues(Expression expression, List<String> result) {
        if (expression instanceof J.NewArray array && array.getInitializer() != null)
            array.getInitializer().forEach(e -> enumValues(e, result));
        else if (expression instanceof J.FieldAccess f) result.add(f.getSimpleName());
    }
}

final class ValidationExtractor extends Extractor {
    private static final Set<String> CONSTRAINTS = Set.of(
            "NotNull",
            "NotBlank",
            "NotEmpty",
            "Size",
            "Min",
            "Max",
            "DecimalMin",
            "DecimalMax",
            "Positive",
            "PositiveOrZero",
            "Negative",
            "NegativeOrZero",
            "Pattern",
            "Email",
            "Past",
            "PastOrPresent",
            "Future",
            "FutureOrPresent");

    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations tree, AnalysisModel model) {
        if (owner() == null) return super.visitVariableDeclarations(tree, model);
        for (var variable : tree.getVariables())
            for (var annotation : tree.getLeadingAnnotations()) {
                String fq = Ast.annotationName(annotation, unit);
                var custom = model.types.get("java:type:" + fq);
                boolean standard = fq.startsWith("jakarta.validation.constraints.")
                        && CONSTRAINTS.contains(annotation.getSimpleName());
                Optional<J.Annotation> constraint = custom == null
                        ? Optional.empty()
                        : Ast.annotation(custom.tree().getLeadingAnnotations(), custom.unit(), "Constraint");
                if (!standard && constraint.isEmpty()) continue;
                String subject = (method() == null ? ownerKey() : method().key()) + "#" + variable.getSimpleName();
                String key = "validation:" + subject + ":" + fq;
                Map<String, Object> props = new TreeMap<>(Ast.arguments(annotation, model, ownerKey()));
                props.put("annotation", fq);
                props.put("field", variable.getSimpleName());
                String operator =
                        switch (annotation.getSimpleName()) {
                            case "Min", "DecimalMin", "PositiveOrZero" -> "GREATER_THAN_OR_EQUAL";
                            case "Max", "DecimalMax", "NegativeOrZero" -> "LESS_THAN_OR_EQUAL";
                            case "Positive" -> "GREATER_THAN";
                            case "Negative" -> "LESS_THAN";
                            case "NotNull" -> "NOT_NULL";
                            default -> annotation.getSimpleName().toUpperCase(Locale.ROOT);
                        };
                if (annotation.getSimpleName().startsWith("Decimal") && Boolean.FALSE.equals(props.get("inclusive")))
                    operator = operator.replace("_OR_EQUAL", "");
                Object value = props.getOrDefault(
                        "value",
                        annotation.getSimpleName().contains("Positive")
                                        || annotation.getSimpleName().contains("Negative")
                                ? 0
                                : "");
                props.put(
                        "condition",
                        Map.of(
                                "type",
                                "VALIDATION",
                                "operator",
                                operator,
                                "field",
                                variable.getSimpleName(),
                                "value",
                                value,
                                "attributes",
                                new TreeMap<>(props)));
                props.put("trueOutcomes", List.of(Map.of("kind", "ALLOW", "scope", "constraint")));
                props.put("falseOutcomes", List.of(Map.of("kind", "REJECT", "scope", "constraint")));
                if (constraint.isPresent()) {
                    props.put("validatorDeclaration", Ast.argumentText(constraint.get(), "validatedBy"));
                    List<String> validators = new ArrayList<>();
                    if (constraint.get().getArguments() != null)
                        for (Expression argument : constraint.get().getArguments()) {
                            if (argument instanceof J.Assignment assignment
                                    && Ast.text(assignment.getVariable()).equals("validatedBy"))
                                validatorTypes(assignment.getAssignment(), custom.unit(), validators);
                        }
                    props.put("validators", validators);
                    for (String validator : validators)
                        if (model.types.containsKey("java:type:" + validator))
                            model.relation(key, "java:type:" + validator, "VALIDATED_BY", unit, tree);
                    props.put("partial", true);
                    model.diagnostic(
                            "CUSTOM_CONSTRAINT_PARTIAL",
                            "Validator declaration is recorded; runtime validation semantics require further analysis",
                            unit,
                            annotation,
                            Map.of("annotation", fq));
                }
                model.fact(
                        "VALIDATION_RULE",
                        key,
                        method() == null ? "FIELD" : "PARAMETER",
                        subject,
                        method() == null ? subject : method().key(),
                        annotation.getSimpleName() + " " + variable.getSimpleName(),
                        props,
                        unit,
                        tree);
                model.relation(method() == null ? subject : method().key(), key, "VALIDATES", unit, tree);
            }
        return super.visitVariableDeclarations(tree, model);
    }

    private static void validatorTypes(Expression expression, SourceUnit unit, List<String> validators) {
        if (expression instanceof J.NewArray array && array.getInitializer() != null)
            array.getInitializer().forEach(e -> validatorTypes(e, unit, validators));
        else if (expression instanceof J.FieldAccess field
                && field.getSimpleName().equals("class"))
            validators.add(Ast.qualified(Ast.text(field.getTarget()), unit));
    }
}

final class AuthorizationExtractor extends Extractor {
    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, AnalysisModel model) {
        extractAnnotations(tree.getLeadingAnnotations(), ownerKey(), tree);
        return super.visitClassDeclaration(tree, model);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        extractAnnotations(tree.getLeadingAnnotations(), model.method(tree).key(), tree);
        return super.visitMethodDeclaration(tree, model);
    }

    private void extractAnnotations(List<J.Annotation> annotations, String subject, J node) {
        for (var annotation : annotations)
            if (Ast.is(annotation, unit, "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed")) {
                var args = Ast.arguments(annotation, model, ownerKey());
                Object value = args.getOrDefault("value", "");
                Map<String, Object> props = new TreeMap<>();
                List<String> roles = new ArrayList<>();
                props.put("annotation", Ast.annotationName(annotation, unit));
                props.put("expression", value);
                if (annotation.getSimpleName().endsWith("Authorize") && value instanceof String expression) {
                    try {
                        props.put(
                                "condition",
                                spel(
                                        new SpelExpressionParser()
                                                .parseRaw(expression)
                                                .getAST(),
                                        roles));
                    } catch (RuntimeException error) {
                        props.put("condition", Map.of("type", "UNKNOWN_EXPRESSION", "source", expression));
                        model.diagnostic(
                                "UNRESOLVED_SECURITY_EXPRESSION",
                                "Security expression could not be parsed",
                                unit,
                                annotation,
                                Map.of());
                    }
                } else {
                    roles.addAll(Ast.strings(value));
                    props.put("condition", Map.of("type", "ROLE_CHECK", "roles", roles));
                }
                props.put("roles", roles);
                props.put("authorization", true);
                props.put("trueOutcomes", List.of(Map.of("kind", "ALLOW")));
                props.put("falseOutcomes", List.of(Map.of("kind", "DENY")));
                String key = "authorization:" + subject + ":" + annotation.getSimpleName();
                model.fact(
                        "AUTHORIZATION_RULE",
                        key,
                        "SYMBOL",
                        subject,
                        subject,
                        annotation.getSimpleName(),
                        props,
                        unit,
                        node);
                model.relation(subject, key, "AUTHORIZED_BY", unit, node);
            }
    }

    private Map<String, Object> spel(SpelNode node, List<String> roles) {
        Map<String, Object> result = new TreeMap<>();
        result.put("type", node.getClass().getSimpleName());
        result.put("source", node.toStringAST());
        List<Object> children = new ArrayList<>();
        for (int i = 0; i < node.getChildCount(); i++) children.add(spel(node.getChild(i), roles));
        result.put("children", children);
        if (node instanceof Literal literal) {
            Object value = literal.getLiteralValue().getValue();
            if (value != null) result.put("value", value);
        }
        if (node instanceof MethodReference method) {
            result.put("method", method.getName());
            if (Set.of("hasRole", "hasAnyRole", "hasAuthority", "hasAnyAuthority")
                    .contains(method.getName())) {
                for (int i = 0; i < node.getChildCount(); i++)
                    if (node.getChild(i) instanceof Literal literal
                            && literal.getLiteralValue().getValue() instanceof String role) roles.add(role);
            }
        }
        return result;
    }
}

final class JpaMappingExtractor extends Extractor {
    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, AnalysisModel model) {
        if (Ast.annotation(tree.getLeadingAnnotations(), unit, "Entity").isPresent()) {
            var table = Ast.annotation(tree.getLeadingAnnotations(), unit, "Table");
            var args = table.map(a -> Ast.arguments(a, model, ownerKey())).orElse(Map.of());
            if (args.get("name") instanceof Map || args.get("schema") instanceof Map) {
                model.diagnostic(
                        "UNRESOLVED_TABLE_NAME", "Table name or schema could not be resolved", unit, tree, Map.of());
                return super.visitClassDeclaration(tree, model);
            }
            String tableName = Objects.toString(args.getOrDefault("name", tree.getSimpleName()));
            String schema = Objects.toString(args.getOrDefault("schema", "public"));
            String tableKey = "db:table:" + schema + "." + tableName;
            model.tables.put(ownerKey(), tableKey);
            model.fact(
                    "ORM_ENTITY",
                    "orm:entity:" + owner().name(),
                    "TYPE",
                    ownerKey(),
                    ownerKey(),
                    tree.getSimpleName(),
                    Map.of("tableKey", tableKey, "mappingExplicit", table.isPresent(), "namingStrategyApplied", false),
                    unit,
                    tree);
            model.fact(
                    "TABLE",
                    tableKey,
                    "TABLE",
                    tableKey,
                    "",
                    tableName,
                    Map.of(
                            "schema",
                            schema,
                            "declarationSource",
                            "JPA_MAPPING",
                            "mappingExplicit",
                            table.isPresent(),
                            "physicalNameVerified",
                            table.isPresent() && args.containsKey("schema")),
                    unit,
                    tree);
            model.relation(ownerKey(), tableKey, "MAPS_TO", unit, tree);
        }
        return super.visitClassDeclaration(tree, model);
    }

    @Override
    public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations tree, AnalysisModel model) {
        if (method() != null || !model.tables.containsKey(ownerKey()))
            return super.visitVariableDeclarations(tree, model);
        if (tree.hasModifier(J.Modifier.Type.Static)
                || Ast.annotation(tree.getLeadingAnnotations(), unit, "Transient")
                        .isPresent()) return super.visitVariableDeclarations(tree, model);
        String table = model.tables.get(ownerKey());
        var association =
                Ast.annotation(tree.getLeadingAnnotations(), unit, "OneToMany", "ManyToOne", "OneToOne", "ManyToMany");
        if (association.isPresent()) {
            String targetType = Ast.typeName(tree.getTypeExpression(), unit);
            if (tree.getTypeExpression() instanceof J.ParameterizedType pt
                    && pt.getTypeParameters() != null
                    && !pt.getTypeParameters().isEmpty())
                targetType = Ast.typeName(pt.getTypeParameters().getLast(), unit);
            for (var variable : tree.getVariables()) {
                String field = ownerKey() + "#" + variable.getSimpleName();
                model.fact(
                        "ORM_RELATIONSHIP",
                        "orm:relationship:" + field,
                        "FIELD",
                        field,
                        ownerKey(),
                        variable.getSimpleName(),
                        Map.of(
                                "relationshipKind",
                                association.get().getSimpleName(),
                                "targetDeclaredType",
                                targetType,
                                "attributes",
                                Ast.arguments(association.get(), model, ownerKey())),
                        unit,
                        tree);
                if (model.types.containsKey("java:type:" + targetType))
                    model.relation(ownerKey(), "java:type:" + targetType, "ASSOCIATED_WITH", unit, tree);
            }
            return super.visitVariableDeclarations(tree, model);
        }
        for (var variable : tree.getVariables()) {
            var columnAnnotation = Ast.annotation(tree.getLeadingAnnotations(), unit, "Column");
            Map<String, Object> props = new TreeMap<>(columnAnnotation
                    .map(a -> Ast.arguments(a, model, ownerKey()))
                    .orElse(Map.of()));
            if (props.get("name") instanceof Map) {
                model.diagnostic("UNRESOLVED_COLUMN_NAME", "Column name could not be resolved", unit, tree, Map.of());
                continue;
            }
            String columnName = Objects.toString(props.getOrDefault("name", variable.getSimpleName()));
            String key = table.replace("db:table:", "db:column:") + "." + columnName;
            props.put("javaFieldKey", ownerKey() + "#" + variable.getSimpleName());
            props.put("tableKey", table);
            props.put("mappingExplicit", columnAnnotation.isPresent());
            props.put("physicalNameVerified", columnAnnotation.isPresent());
            props.putIfAbsent("nullable", true);
            props.putIfAbsent("unique", false);
            props.putIfAbsent("length", 255);
            props.putIfAbsent("insertable", true);
            props.putIfAbsent("updatable", true);
            props.put(
                    "id",
                    Ast.annotation(tree.getLeadingAnnotations(), unit, "Id", "EmbeddedId")
                            .isPresent());
            for (var annotation : tree.getLeadingAnnotations())
                if (Ast.is(
                        annotation,
                        unit,
                        "GeneratedValue",
                        "Enumerated",
                        "OneToMany",
                        "ManyToOne",
                        "OneToOne",
                        "ManyToMany",
                        "JoinColumn",
                        "ColumnDefault")) {
                    props.put(annotation.getSimpleName(), Ast.arguments(annotation, model, ownerKey()));
                }
            model.fact(
                    "COLUMN",
                    key,
                    "FIELD",
                    ownerKey() + "#" + variable.getSimpleName(),
                    table,
                    columnName,
                    props,
                    unit,
                    tree);
            model.relation(ownerKey() + "#" + variable.getSimpleName(), key, "MAPS_TO", unit, tree);
            var relationship = Ast.annotation(
                    tree.getLeadingAnnotations(), unit, "OneToMany", "ManyToOne", "OneToOne", "ManyToMany");
            if (relationship.isPresent()) {
                String target = "java:type:" + Ast.typeName(tree.getTypeExpression(), unit);
                if (model.types.containsKey(target)) model.relation(ownerKey(), target, "ASSOCIATED_WITH", unit, tree);
            }
        }
        return super.visitVariableDeclarations(tree, model);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        for (var annotation : tree.getLeadingAnnotations())
            if (Ast.is(
                    annotation,
                    unit,
                    "PrePersist",
                    "PreUpdate",
                    "PostPersist",
                    "PostLoad",
                    "PreRemove",
                    "PostRemove")) {
                String key = "orm:callback:" + model.method(tree).key() + ":" + annotation.getSimpleName();
                model.fact(
                        "ORM_LIFECYCLE_CALLBACK",
                        key,
                        "METHOD",
                        model.method(tree).key(),
                        ownerKey(),
                        annotation.getSimpleName(),
                        Map.of("callback", annotation.getSimpleName()),
                        unit,
                        tree);
                model.relation(key, model.method(tree).key(), "TRIGGERS", unit, tree);
            }
        return super.visitMethodDeclaration(tree, model);
    }
}

final class RepositoryQueryExtractor extends Extractor {
    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        if (!model.repositoryEntities.containsKey(ownerKey())) return super.visitMethodDeclaration(tree, model);
        String subject = model.method(tree).key();
        Map<String, Object> props = new TreeMap<>();
        props.put("entityKey", model.repositoryEntities.get(ownerKey()));
        props.put("ormOperation", true);
        var query = Ast.annotation(tree.getLeadingAnnotations(), unit, "Query");
        if (query.isPresent()) {
            props.putAll(Ast.arguments(query.get(), model, ownerKey()));
            props.put("queryLanguage", Boolean.TRUE.equals(props.get("nativeQuery")) ? "SQL" : "JPQL");
            props.put("exactSql", Boolean.TRUE.equals(props.get("nativeQuery")));
        } else {
            int by = tree.getSimpleName().indexOf("By");
            props.put("derivedQuery", tree.getSimpleName());
            if (by >= 0)
                props.put(
                        "derivedPredicate",
                        Map.of(
                                "type",
                                "SPRING_DATA_METHOD_NAME",
                                "source",
                                tree.getSimpleName().substring(by + 2),
                                "fullyResolved",
                                false));
            props.put("exactSql", false);
        }
        props.put(
                "modifying",
                Ast.annotation(tree.getLeadingAnnotations(), unit, "Modifying").isPresent());
        model.fact(
                "REPOSITORY_QUERY",
                "orm:query:" + subject,
                "METHOD",
                subject,
                subject,
                tree.getSimpleName(),
                props,
                unit,
                tree);
        return super.visitMethodDeclaration(tree, model);
    }
}

final class TransactionExtractor extends Extractor {
    @Override
    public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration tree, AnalysisModel model) {
        record(tree.getLeadingAnnotations(), ownerKey(), tree);
        return super.visitClassDeclaration(tree, model);
    }

    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        record(tree.getLeadingAnnotations(), model.method(tree).key(), tree);
        return super.visitMethodDeclaration(tree, model);
    }

    private void record(List<J.Annotation> annotations, String subject, J tree) {
        Ast.annotation(annotations, unit, "Transactional")
                .ifPresent(annotation -> model.fact(
                        "TRANSACTION",
                        "transaction:" + subject,
                        "SYMBOL",
                        subject,
                        subject,
                        "Transactional",
                        Map.of(
                                "annotation",
                                Ast.annotationName(annotation, unit),
                                "attributes",
                                Ast.arguments(annotation, model, ownerKey()),
                                "proxyInvocationVerified",
                                false),
                        unit,
                        tree));
    }
}

final class ScheduledJobExtractor extends Extractor {
    @Override
    public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration tree, AnalysisModel model) {
        Ast.annotation(tree.getLeadingAnnotations(), unit, "Scheduled").ifPresent(annotation -> {
            String subject = model.method(tree).key(), key = "spring:scheduled:" + subject;
            model.fact(
                    "SCHEDULED_JOB",
                    key,
                    "METHOD",
                    subject,
                    ownerKey(),
                    tree.getSimpleName(),
                    Ast.arguments(annotation, model, ownerKey()),
                    unit,
                    tree);
            model.relation(key, subject, "ENTRY_TO", unit, tree);
        });
        return super.visitMethodDeclaration(tree, model);
    }
}
