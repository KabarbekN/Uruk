package io.semanticmap.platform.repository;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class RepositorySnapshotsTest {
    @TempDir
    Path temp;

    private RepositoryPolicy policy;
    private RepositorySnapshots snapshots;
    private Path repository;

    @BeforeEach
    void setup() throws IOException {
        repository = Files.createDirectory(temp.resolve("repository"));
        policy = new RepositoryPolicy(new MockEnvironment()
                .withProperty("semantic.repository-roots", repository.toString())
                .withProperty(
                        "semantic.artifact-root", temp.resolve("artifacts").toString()));
        snapshots = new RepositorySnapshots(policy, new GitClient(policy));
    }

    private RepositorySnapshots.Snapshot snapshot() throws IOException {
        return snapshots.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                repository.toString(),
                "HEAD",
                null,
                () -> false);
    }

    @Test
    void snapshotsAreIndependentDeterministicAndFiltered() throws IOException {
        Files.writeString(repository.resolve("App.java"), "class App {}\n");
        Files.writeString(repository.resolve(".env"), "SECRET=hidden");
        Files.createDirectories(repository.resolve("target"));
        Files.writeString(repository.resolve("target/generated.java"), "generated");
        Files.writeString(repository.resolve("skip.txt"), "ignored");
        Files.writeString(repository.resolve(".semanticmapignore"), "skip.txt\n");
        Files.write(repository.resolve("binary.bin"), new byte[] {1, 0, 2});
        var first = snapshot();
        var second = snapshot();
        assertThat(first.fingerprint()).isEqualTo(second.fingerprint());
        assertThat(first.files())
                .containsKey("App.java")
                .doesNotContainKeys(".env", "skip.txt", "target/generated.java", "binary.bin");
        Files.writeString(repository.resolve("App.java"), "class Changed {}\n");
        var third = snapshot();
        assertThat(Files.readString(first.workspace().resolve("App.java"))).isEqualTo("class App {}\n");
        assertThat(third.fingerprint()).isNotEqualTo(first.fingerprint());
        assertThat(snapshots.changedFiles(third, first.workspace()))
                .containsExactly(new RepositorySnapshots.ChangedFile("App.java", "MODIFIED"));
    }

    @Test
    void rejectsTraversalAndOutsideRoots() {
        assertThatThrownBy(() -> policy.localRoot(temp.toString())).isInstanceOf(IllegalArgumentException.class);
        for (String path :
                new String[] {"../outside", "/outside", "C:/outside", "folder/../../outside", "folder\\outside"})
            assertThatThrownBy(() -> RepositorySnapshots.safeChild(repository, path))
                    .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> snapshots.remove(temp)).isInstanceOf(IOException.class);
    }

    @Test
    void rejectsOversizedFilesAndCancellation() throws IOException {
        var limited = new RepositoryPolicy(new MockEnvironment()
                .withProperty("semantic.repository-roots", repository.toString())
                .withProperty(
                        "semantic.artifact-root", temp.resolve("artifacts").toString())
                .withProperty("semantic.repository.max-file-bytes", "4"));
        Files.writeString(repository.resolve("file.txt"), "too large");
        var service = new RepositorySnapshots(limited, new GitClient(limited));
        assertThatThrownBy(() -> service.create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        repository.toString(),
                        "HEAD",
                        null,
                        () -> false))
                .hasMessage("REPOSITORY_FILE_SIZE_LIMIT");
        assertThatThrownBy(() -> snapshots.create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        repository.toString(),
                        "HEAD",
                        null,
                        () -> true))
                .hasMessage("PROCESS_CANCELLED");
    }

    @Test
    void productionRequiresHostAllowlistAndNoLocalPaths() {
        var env = new MockEnvironment().withProperty("semantic.production", "true");
        var production = new RepositoryPolicy(env);
        assertThatThrownBy(() -> production.validate("https://internal.invalid/repo.git"))
                .hasMessage("PRODUCTION_REQUIRES_REPOSITORY_HOST_ALLOWLIST");
        env.setProperty("semantic.repository-hosts", "git.example.org");
        assertThatCode(() -> production.validate("https://git.example.org/repo.git"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> production.validate("https://git.example.org.evil.invalid/repo.git"))
                .hasMessage("REPOSITORY_HOST_NOT_ALLOWED");
        assertThatThrownBy(() -> production.validate(repository.toString())).hasMessage("LOCAL_REPOSITORIES_DISABLED");
        assertThatThrownBy(() -> production.validate("https://token@git.example.org/repo.git"))
                .hasMessage("INVALID_REPOSITORY_URL");
        assertThatThrownBy(() -> production.validate("file:///tmp/repo")).hasMessage("UNSUPPORTED_REPOSITORY_PROTOCOL");
    }

    @Test
    void ignoreSupportsRecursiveGlobsAnchorsAndOrderedNegation() {
        var rules = new IgnoreRules("**/*.generated.java\n/docs/\n*.txt\n!important.txt\n");
        assertThat(rules.ignored("A.generated.java")).isTrue();
        assertThat(rules.ignored("src/a/A.generated.java")).isTrue();
        assertThat(rules.ignored("docs/a.md")).isTrue();
        assertThat(rules.ignored("other/docs/a.md")).isFalse();
        assertThat(rules.ignored("important.txt")).isFalse();
        assertThat(new IgnoreRules("!node_modules/**").ignored("node_modules/pkg/index.js"))
                .isTrue();
    }

    @Test
    void symlinksNeverEnterSnapshot() throws IOException {
        Path external = Files.writeString(temp.resolve("outside.txt"), "secret");
        try {
            Files.createSymbolicLink(repository.resolve("linked.txt"), external);
        } catch (IOException | UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.abort("Host cannot create test symlinks");
        }
        assertThatThrownBy(this::snapshot).isInstanceOf(IOException.class).hasMessageContaining("SYMLINK");
    }
}
