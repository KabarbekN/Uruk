package io.semanticmap.platform.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.repository.IgnoreRules;
import io.semanticmap.platform.repository.RepositoryPolicy;
import io.semanticmap.platform.repository.RepositorySnapshots;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

@Component
public class TechnologyDetector {
    private final ObjectMapper mapper;

    public TechnologyDetector(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public record DetectedComponent(
            String rootPath,
            List<String> languages,
            List<String> frameworks,
            List<String> buildSystems,
            List<String> databaseTechnologies,
            List<Map<String, Object>> evidence,
            String fingerprint,
            List<Map<String, Object>> diagnostics) {}

    public List<DetectedComponent> detect(Path workspace) throws IOException {
        RepositoryPolicy.rejectLinks(workspace);
        IgnoreRules ignore = IgnoreRules.load(workspace);
        List<Path> files;
        try (var paths = Files.walk(workspace)) {
            files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !ignore.ignored(relative(workspace, p)))
                    .sorted()
                    .toList();
        }
        var components = new TreeMap<String, Mutable>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (isManifest(name)) components.computeIfAbsent(relative(workspace, file.getParent()), Mutable::new);
        }
        components.computeIfAbsent(".", Mutable::new);
        for (Path file : files) {
            RepositoryPolicy.rejectLinks(file);
            if (Files.size(file) > 4194304) throw new IOException("DETECTION_FILE_LIMIT");
            String path = relative(workspace, file);
            Mutable component = components.get(".");
            for (var candidate : components.values()) {
                if (!candidate.root.equals(".")
                        && path.startsWith(candidate.root + "/")
                        && (component.root.equals(".") || candidate.root.length() > component.root.length()))
                    component = candidate;
            }
            byte[] bytes = Files.readAllBytes(file);
            component.hashes.put(path, RepositorySnapshots.hashBytes(bytes));
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            String language = language(name, text);
            if (language != null) {
                component.languages.add(language);
                component.evidence(path, "FILE_EXTENSION_OR_SHEBANG", 0.99);
            }
            try {
                if (name.equals("pom.xml") || name.endsWith(".csproj")) xml(component, path, bytes, name);
                else if (name.equals("package.json") || name.equals("composer.json")) json(component, path, text, name);
                else if (name.startsWith("build.gradle") || name.startsWith("settings.gradle"))
                    gradle(component, path, text);
                else if (name.equals("go.mod")) {
                    component.languages.add("GO");
                    component.build.add("GO_MODULES");
                    component.evidence(path, "GO_MODULE_MANIFEST", 0.95);
                } else if (name.equals("Cargo.toml")) {
                    component.languages.add("RUST");
                    component.build.add("CARGO");
                    component.evidence(path, "CARGO_MANIFEST", 0.95);
                } else if (name.equals("pyproject.toml")
                        || name.equals("requirements.txt")
                        || name.equals("poetry.lock")) {
                    component.languages.add("PYTHON");
                    component.build.add(name.equals("poetry.lock") ? "POETRY" : "PYTHON_PACKAGING");
                    component.evidence(path, "PYTHON_MANIFEST", 0.95);
                } else if (name.equals("Gemfile")) {
                    component.languages.add("RUBY");
                    component.build.add("BUNDLER");
                }
                if ("JAVA".equals(language) || "KOTLIN".equals(language)) {
                    if (text.matches("(?s).*\\bimport\\s+org\\.springframework\\..*")) {
                        component.frameworks.add("SPRING_BOOT");
                        component.evidence(path, "SPRING_IMPORT", 0.95);
                    }
                    if (text.matches("(?s).*\\bimport\\s+(jakarta|javax)\\.persistence\\..*"))
                        component.frameworks.add("JPA");
                }
                if ("SQL".equals(language)) {
                    component.databases.add("POSTGRESQL");
                    if (path.contains("/migration/") || name.matches("V[0-9].*__.*\\.sql"))
                        component.databases.add("FLYWAY");
                    component.evidence(path, "SQL_SOURCE", 0.8);
                }
                if (path.contains("db/changelog") || name.toLowerCase().contains("liquibase"))
                    component.databases.add("LIQUIBASE");
            } catch (IOException | org.xml.sax.SAXException | javax.xml.parsers.ParserConfigurationException e) {
                component.diagnostics.add(Map.of(
                        "code",
                        "MALFORMED_MANIFEST",
                        "severity",
                        "WARNING",
                        "filePath",
                        path,
                        "message",
                        "Manifest could not be parsed; detection uses remaining evidence"));
            }
        }
        return components.values().stream()
                .filter(c -> !c.languages.isEmpty() || !c.build.isEmpty())
                .map(Mutable::result)
                .toList();
    }

    private void xml(Mutable c, String path, byte[] bytes, String name)
            throws javax.xml.parsers.ParserConfigurationException, IOException, org.xml.sax.SAXException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override
            public void fatalError(org.xml.sax.SAXParseException e) throws org.xml.sax.SAXException {
                throw e;
            }
        });
        var document = builder.parse(new java.io.ByteArrayInputStream(bytes));
        if (name.endsWith(".csproj")) {
            c.languages.add("CSHARP");
            c.build.add("MSBUILD");
            c.evidence(path, "MSBUILD_MANIFEST", 1);
            return;
        }
        if (!document.getDocumentElement().getTagName().equals("project"))
            throw new IOException("INVALID_MAVEN_PROJECT");
        c.build.add("MAVEN");
        var dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            coordinate(c, child(dependency, "groupId") + ":" + child(dependency, "artifactId"));
        }
        var parents = document.getElementsByTagName("parent");
        for (int i = 0; i < parents.getLength(); i++) {
            Element parent = (Element) parents.item(i);
            coordinate(c, child(parent, "groupId") + ":" + child(parent, "artifactId"));
        }
        c.evidence(path, "MAVEN_XML", 1);
    }

    private void json(Mutable c, String path, String text, String name) throws IOException {
        JsonNode root = mapper.readTree(text);
        if (root == null || !root.isObject()) throw new IOException("INVALID_PACKAGE_MANIFEST");
        if (name.equals("composer.json")) {
            c.languages.add("PHP");
            c.build.add("COMPOSER");
            c.evidence(path, "COMPOSER_JSON", 1);
            return;
        }
        c.build.add("NPM");
        for (String field : List.of("dependencies", "devDependencies", "peerDependencies")) {
            JsonNode dependencies = root.path(field);
            if (!dependencies.isMissingNode() && !dependencies.isObject())
                throw new IOException("INVALID_DEPENDENCIES");
            dependencies.fieldNames().forEachRemaining(dep -> {
                switch (dep) {
                    case "typescript" -> c.languages.add("TYPESCRIPT");
                    case "@nestjs/core", "@nestjs/common" -> c.frameworks.add("NESTJS");
                    case "express" -> c.frameworks.add("EXPRESS");
                    case "react" -> c.frameworks.add("REACT");
                    case "pg" -> c.databases.add("POSTGRESQL");
                    case "vite" -> c.build.add("VITE");
                    default -> {}
                }
            });
        }
        c.evidence(path, "PACKAGE_JSON_DEPENDENCIES", 1);
    }

    private void gradle(Mutable c, String path, String text) {
        c.build.add("GRADLE");
        var matcher = java.util.regex.Pattern.compile("[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)(?::[^\"']*)?[\"']")
                .matcher(text);
        while (matcher.find()) coordinate(c, matcher.group(1));
        if (text.matches("(?s).*\\bid\\s*\\(?[\"']org\\.springframework\\.boot[\"'].*"))
            c.frameworks.add("SPRING_BOOT");
        c.evidence(path, "GRADLE_LITERAL_COORDINATES", 0.85);
    }

    private static void coordinate(Mutable c, String coordinate) {
        if (coordinate.startsWith("org.springframework.boot:")) c.frameworks.add("SPRING_BOOT");
        if (coordinate.startsWith("org.springframework:") && coordinate.contains("web")) c.frameworks.add("SPRING_MVC");
        if (coordinate.contains("data-jpa")
                || coordinate.startsWith("org.hibernate:")
                || coordinate.startsWith("jakarta.persistence:")) c.frameworks.add("JPA");
        if (coordinate.startsWith("org.postgresql:")) c.databases.add("POSTGRESQL");
        if (coordinate.startsWith("org.flywaydb:")) c.databases.add("FLYWAY");
        if (coordinate.startsWith("org.liquibase:")) c.databases.add("LIQUIBASE");
    }

    private static String child(Element e, String name) {
        var nodes = e.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().strip();
    }

    private static String relative(Path root, Path file) {
        String value = root.relativize(file).toString().replace('\\', '/');
        return value.isEmpty() ? "." : value;
    }

    private static boolean isManifest(String name) {
        return Set.of(
                                "pom.xml",
                                "build.gradle",
                                "build.gradle.kts",
                                "package.json",
                                "composer.json",
                                "go.mod",
                                "Cargo.toml",
                                "pyproject.toml",
                                "Gemfile")
                        .contains(name)
                || name.endsWith(".csproj");
    }

    private static String language(String name, String text) {
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return switch (extension) {
            case "java" -> "JAVA";
            case "kt", "kts" -> name.startsWith("build.gradle") || name.startsWith("settings.gradle") ? null : "KOTLIN";
            case "ts", "tsx" -> "TYPESCRIPT";
            case "js", "jsx", "mjs", "cjs" -> "JAVASCRIPT";
            case "sql" -> "SQL";
            case "py" -> "PYTHON";
            case "go" -> "GO";
            case "cs" -> "CSHARP";
            case "rs" -> "RUST";
            case "rb" -> "RUBY";
            case "php" -> "PHP";
            default -> text.startsWith("#!")
                            && text.lines().findFirst().orElse("").contains("python")
                    ? "PYTHON"
                    : null;
        };
    }

    private static final class Mutable {
        final String root;
        final Set<String> languages = new TreeSet<>(),
                frameworks = new TreeSet<>(),
                build = new TreeSet<>(),
                databases = new TreeSet<>();
        final Map<String, String> hashes = new TreeMap<>();
        final Map<String, Map<String, Object>> evidence = new LinkedHashMap<>();
        final List<Map<String, Object>> diagnostics = new ArrayList<>();

        Mutable(String root) {
            this.root = root;
        }

        void evidence(String path, String type, double confidence) {
            if (evidence.size() < 200)
                evidence.put(path, Map.of("filePath", path, "type", type, "confidence", confidence));
        }

        DetectedComponent result() {
            return new DetectedComponent(
                    root,
                    List.copyOf(languages),
                    List.copyOf(frameworks),
                    List.copyOf(build),
                    List.copyOf(databases),
                    List.copyOf(evidence.values()),
                    RepositorySnapshots.fingerprint(hashes),
                    List.copyOf(diagnostics));
        }
    }
}
