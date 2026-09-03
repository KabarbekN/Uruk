package io.semanticmap.platform.repository;

import io.semanticmap.platform.runner.BoundedProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;

@Component
public class GitClient {
    private final RepositoryPolicy policy;

    public GitClient(RepositoryPolicy policy) {
        this.policy = policy;
    }

    public record Entry(String mode, String sha, String path) {}

    public record Tree(Path gitDirectory, String commit, List<String> parents, List<Entry> entries) {}

    public void test(String url, String ref, String credentials) throws IOException {
        policy.validate(url);
        policy.validateRef(ref);
        policy.validateCredentials(credentials);
        if (!policy.remote(url)) {
            policy.localRoot(url);
            return;
        }
        run(null, credentials, List.of("ls-remote", "--exit-code", "--", url, ref), 1048576, () -> false);
    }

    public Tree fetch(String url, String ref, String credentials, Path bare, BooleanSupplier cancelled)
            throws IOException {
        policy.validate(url);
        policy.validateRef(ref);
        policy.validateCredentials(credentials);
        Files.createDirectories(bare);
        run(null, null, List.of("init", "--bare", "--template=", bare.toString()), 65536, cancelled);
        BooleanSupplier guard = () -> cancelled.getAsBoolean() || exceedsDiskBudget(bare);
        run(
                bare,
                credentials,
                List.of("fetch", "--no-tags", "--depth=1", "--no-recurse-submodules", "--", url, ref),
                1048576,
                guard);
        String sha = run(bare, null, List.of("rev-parse", "--verify", "FETCH_HEAD^{commit}"), 256, cancelled)
                .text();
        if (!sha.matches("[a-f0-9]{40,64}")) throw new IOException("INVALID_GIT_COMMIT");
        String commit = run(bare, null, List.of("cat-file", "commit", sha), 1048576, cancelled)
                .text();
        var parents = commit.lines()
                .takeWhile(line -> !line.isEmpty())
                .filter(line -> line.startsWith("parent "))
                .map(line -> line.substring(7))
                .toList();
        String tree = new String(
                run(bare, null, List.of("ls-tree", "-r", "-z", "--full-tree", sha), 16777216, cancelled)
                        .stdout(),
                StandardCharsets.UTF_8);
        var entries = new ArrayList<Entry>();
        for (String record : tree.split("\u0000")) {
            if (record.isEmpty()) continue;
            int tab = record.indexOf('\t');
            if (tab < 0) throw new IOException("INVALID_GIT_TREE");
            String[] metadata = record.substring(0, tab).split(" ");
            if (metadata.length != 3) throw new IOException("INVALID_GIT_TREE");
            if (metadata[0].equals("160000")) continue; // Submodule contents never enter the snapshot.
            if (!metadata[0].equals("100644") && !metadata[0].equals("100755"))
                throw new IOException("GIT_SYMLINK_REJECTED");
            entries.add(new Entry(metadata[0], metadata[2], record.substring(tab + 1)));
            if (entries.size() > policy.maxFiles()) throw new IOException("REPOSITORY_FILE_LIMIT");
        }
        return new Tree(bare, sha, parents, entries);
    }

    public byte[] blob(Tree tree, Entry entry, BooleanSupplier cancelled) throws IOException {
        if (!entry.sha().matches("[a-f0-9]{40,64}")) throw new IOException("INVALID_GIT_OBJECT");
        return run(
                        tree.gitDirectory(),
                        null,
                        List.of("cat-file", "blob", entry.sha()),
                        policy.maxFileBytes(),
                        cancelled)
                .stdout();
    }

    private BoundedProcess.Result run(
            Path bare, String reference, List<String> args, long limit, BooleanSupplier cancelled) throws IOException {
        var command = new ArrayList<>(List.of(
                "git",
                "-c",
                "core.hooksPath=",
                "-c",
                "core.fsmonitor=false",
                "-c",
                "credential.helper=",
                "-c",
                "http.followRedirects=false",
                "-c",
                "protocol.allow=never",
                "-c",
                "protocol.https.allow=always",
                "-c",
                "protocol.ssh.allow=always",
                "-c",
                "protocol.file.allow=always",
                "-c",
                "submodule.recurse=false"));
        if (bare != null) command.addAll(List.of("--git-dir", bare.toString()));
        command.addAll(args);
        var env = new HashMap<String, String>();
        env.put("GIT_TERMINAL_PROMPT", "0");
        env.put("GIT_CONFIG_NOSYSTEM", "1");
        env.put("GIT_CONFIG_GLOBAL", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null");
        env.put("GIT_LFS_SKIP_SMUDGE", "1");
        env.put("GIT_CONFIG_COUNT", "0");
        env.put(
                "GIT_SSH_COMMAND",
                "ssh -oBatchMode=yes -oStrictHostKeyChecking=yes -oIdentitiesOnly=yes -oIdentityAgent=none -oIdentityFile=none");
        if (reference != null) {
            String token = policy.property("semantic.repository.credentials." + reference + ".https-token");
            String key = policy.property("semantic.repository.credentials." + reference + ".ssh-key-path");
            if (token != null) {
                if (token.chars().anyMatch(c -> c < 32)) throw new IOException("INVALID_CREDENTIAL");
                String username = policy.property("semantic.repository.credentials." + reference + ".https-username");
                if (username == null) username = "oauth2";
                if (!username.matches("[A-Za-z0-9_.@-]+")) throw new IOException("INVALID_CREDENTIAL_USERNAME");
                env.put("GIT_CONFIG_COUNT", "1");
                env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
                env.put(
                        "GIT_CONFIG_VALUE_0",
                        "Authorization: Basic "
                                + java.util.Base64.getEncoder()
                                        .encodeToString((username + ":" + token).getBytes(StandardCharsets.UTF_8)));
            } else if (key != null) {
                Path keyPath = Path.of(key).toAbsolutePath().normalize();
                RepositoryPolicy.rejectLinks(keyPath);
                if (!Files.isRegularFile(keyPath) || !keyPath.toString().matches("[A-Za-z0-9 _./:\\\\-]+"))
                    throw new IOException("INVALID_SSH_KEY_PATH");
                env.put(
                        "GIT_SSH_COMMAND",
                        env.get("GIT_SSH_COMMAND") + " -i \""
                                + keyPath.toString().replace('\\', '/') + "\"");
            } else throw new IOException("CREDENTIAL_REFERENCE_UNAVAILABLE");
        }
        var result =
                BoundedProcess.run(command, null, env, Duration.ofSeconds(policy.timeoutSeconds()), limit, cancelled);
        if (result.exitCode() != 0) throw new IOException("GIT_OPERATION_FAILED");
        return result;
    }

    private boolean exceedsDiskBudget(Path root) {
        try (var paths = Files.walk(root)) {
            long total = 0;
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (Files.isRegularFile(path)) total += Files.size(path);
                if (total > policy.maxBytes()) return true;
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
