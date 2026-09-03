package io.semanticmap.platform.detection;

import io.semanticmap.platform.shared.Db;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class AnalyzerResolver {
    private final Db db;
    private final Environment environment;

    public AnalyzerResolver(Db db, Environment environment) {
        this.db = db;
        this.environment = environment;
    }

    public record Plan(
            String analyzerKey, String version, String imageReference, List<String> capabilities, String reason) {}

    public List<Plan> resolve(List<String> languages, List<String> frameworks, List<String> databases) {
        var definitions = db.rows(
                "SELECT * FROM analyzer_definition WHERE enabled=true AND retired_at IS NULL AND contract_version LIKE '1.%' AND requires_dependency_resolution=false AND network_policy='DENY' ORDER BY analyzer_key, created_at DESC");
        var result = new ArrayList<Plan>();
        Set<String> selected = new HashSet<>();
        for (var definition : definitions) {
            String key = (String) definition.get("analyzerKey");
            List<String> supported = strings(definition.get("supportedLanguages"));
            List<String> supportedFrameworks = strings(definition.get("supportedFrameworks"));
            boolean matches = languages.stream().anyMatch(supported::contains);
            if (key.equals("postgresql"))
                matches = languages.contains("SQL")
                        || databases.contains("POSTGRESQL")
                        || databases.contains("FLYWAY")
                        || databases.contains("LIQUIBASE");
            if (!matches || selected.contains(key)) continue;
            if (!supportedFrameworks.isEmpty()
                    && !key.equals("java-spring")
                    && !key.equals("typescript-node")
                    && !key.equals("postgresql")
                    && frameworks.stream().noneMatch(supportedFrameworks::contains)) continue;
            selected.add(key);
            String image = environment.getProperty(
                    "semantic.analyzers." + key + ".image", String.valueOf(definition.get("imageReference")));
            if (definition.get("imageDigest") != null
                    && !image.contains("@")
                    && environment.getProperty("semantic.analyzers." + key + ".image") == null)
                image = image.replaceFirst(":([^/:]+)$", "") + "@" + definition.get("imageDigest");
            result.add(new Plan(
                    key,
                    (String) definition.get("version"),
                    image,
                    strings(definition.get("capabilities")),
                    key.equals("postgresql")
                            ? "SQL or database migration evidence detected"
                            : "Detected languages " + languages + "; frameworks " + frameworks));
        }
        return List.copyOf(result);
    }

    public static List<String> strings(Object value) {
        return value instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
    }
}
