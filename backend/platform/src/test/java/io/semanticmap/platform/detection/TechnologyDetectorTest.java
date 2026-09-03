package io.semanticmap.platform.detection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.shared.Db;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class TechnologyDetectorTest {
    @TempDir
    Path root;

    @Test
    void detectsSeparateComponentsFromStructuredManifests() throws Exception {
        Files.createDirectories(root.resolve("backend/src"));
        Files.createDirectories(root.resolve("frontend/src"));
        Files.writeString(
                root.resolve("backend/pom.xml"),
                "<project><dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency><dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId></dependency></dependencies></project>");
        Files.writeString(root.resolve("backend/src/App.java"), "class App {}\n");
        Files.writeString(
                root.resolve("frontend/package.json"),
                "{\"dependencies\":{\"react\":\"19\"},\"devDependencies\":{\"typescript\":\"5\",\"vite\":\"7\"}}");
        Files.writeString(root.resolve("frontend/src/App.tsx"), "export const app = 1;");
        var components = new TechnologyDetector(new ObjectMapper()).detect(root);
        assertThat(components).hasSize(2);
        assertThat(components.getFirst().rootPath()).isEqualTo("backend");
        assertThat(components.getFirst().languages()).containsExactly("JAVA");
        assertThat(components.getFirst().frameworks()).contains("SPRING_BOOT", "JPA");
        assertThat(components.getFirst().databaseTechnologies()).contains("POSTGRESQL");
        assertThat(components.getLast().languages()).containsExactly("TYPESCRIPT");
        assertThat(components.getLast().frameworks()).containsExactly("REACT");
    }

    @Test
    void rejectsXmlEntitiesAndReportsMalformedManifest() throws Exception {
        Files.writeString(
                root.resolve("pom.xml"),
                "<!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><project>&secret;</project>");
        Files.writeString(root.resolve("App.java"), "class App {}\n");
        var component = new TechnologyDetector(new ObjectMapper()).detect(root).getFirst();
        assertThat(component.diagnostics()).extracting(d -> d.get("code")).contains("MALFORMED_MANIFEST");
        assertThat(component.frameworks()).isEmpty();
    }

    @Test
    void ignoresExcludedSourcesBeforeDetection() throws Exception {
        Files.writeString(root.resolve(".semanticmapignore"), "ignored/**\n");
        Files.createDirectories(root.resolve("ignored"));
        Files.writeString(root.resolve("ignored/App.ts"), "export const app=1");
        Files.writeString(root.resolve("App.java"), "class App {}\n");
        assertThat(new TechnologyDetector(new ObjectMapper())
                        .detect(root)
                        .getFirst()
                        .languages())
                .containsExactly("JAVA");
    }

    @Test
    void resolverSelectsOnlyDetectedEcosystems() {
        Db db = mock(Db.class);
        when(db.rows(anyString()))
                .thenReturn(List.of(
                        definition("tree-sitter", List.of("JAVA", "TYPESCRIPT")),
                                definition("java-spring", List.of("JAVA")),
                        definition("postgresql", List.of("SQL")),
                                definition("typescript-node", List.of("TYPESCRIPT"))));
        var resolver = new AnalyzerResolver(db, new MockEnvironment());
        assertThat(resolver.resolve(List.of("JAVA"), List.of("SPRING_BOOT"), List.of("POSTGRESQL")))
                .extracting(AnalyzerResolver.Plan::analyzerKey)
                .containsExactly("tree-sitter", "java-spring", "postgresql");
        assertThat(resolver.resolve(List.of("PYTHON"), List.of(), List.of())).isEmpty();
    }

    private Map<String, Object> definition(String key, List<String> languages) {
        return Map.of(
                "analyzerKey",
                key,
                "version",
                "0.1.0",
                "supportedLanguages",
                languages,
                "supportedFrameworks",
                key.equals("postgresql") ? List.of("POSTGRESQL", "FLYWAY", "LIQUIBASE") : List.of(),
                "capabilities",
                List.of("SYMBOLS"),
                "imageReference",
                "semanticmap/" + key + ":0.1.0");
    }
}
