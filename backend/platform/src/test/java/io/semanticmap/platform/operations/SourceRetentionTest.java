package io.semanticmap.platform.operations;

import static org.assertj.core.api.Assertions.*;

import io.semanticmap.platform.storage.LocalFileArtifactStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceRetentionTest {
    @TempDir
    Path root;

    @Test
    void cannotDeleteRootOrOutsideSource() {
        var artifacts = new LocalFileArtifactStore(root.toString());
        UUID org = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
        assertThatThrownBy(() -> SourceRetention.deleteRunArtifacts(artifacts, root, org, project, run, root))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> SourceRetention.deleteRunArtifacts(artifacts, root, org, project, run, root.getParent()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expiresSourceAndExecutionOutputsForOnlyTheSelectedRun() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
        Path projectRoot =
                root.resolve("workspaces").resolve(organization.toString()).resolve(project.toString());
        Path attempt =
                projectRoot.resolve(run.toString()).resolve(UUID.randomUUID().toString());
        Path source = Files.createDirectories(attempt.resolve("source"));
        Path output = Files.createDirectories(attempt.resolve("executions").resolve("output"));
        Files.writeString(output.resolve("facts.ndjson"), "source evidence");
        Path retained =
                Files.createDirectories(projectRoot.resolve(UUID.randomUUID().toString()));
        SourceRetention.deleteRunArtifacts(
                new LocalFileArtifactStore(root.toString()), root, organization, project, run, source);
        assertThat(attempt.getParent()).doesNotExist();
        assertThat(retained).exists();
    }

    @Test
    void cannotExpireArtifactsFromAnotherRun() throws Exception {
        UUID organization = UUID.randomUUID(), project = UUID.randomUUID(), run = UUID.randomUUID();
        Path otherSource = Files.createDirectories(root.resolve("workspaces")
                .resolve(organization.toString())
                .resolve(project.toString())
                .resolve(UUID.randomUUID().toString())
                .resolve(UUID.randomUUID().toString())
                .resolve("source"));
        assertThatThrownBy(() -> SourceRetention.deleteRunArtifacts(
                        new LocalFileArtifactStore(root.toString()), root, organization, project, run, otherSource))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(otherSource).exists();
    }
}
