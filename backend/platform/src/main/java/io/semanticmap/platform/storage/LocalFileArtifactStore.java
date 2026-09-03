package io.semanticmap.platform.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.artifact-store.type", havingValue = "local", matchIfMissing = true)
public class LocalFileArtifactStore implements ArtifactStore {
    private static final int MAX_BYTES = 128 * 1024 * 1024;
    private final Path root;

    public LocalFileArtifactStore(@Value("${semantic.artifact-root:.runtime/artifacts}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public Metadata write(String key, InputStream source, long maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid artifact size limit");
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        resolve(key);
        MessageDigest hash;
        try {
            hash = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        long count = 0;
        boolean created = false;
        try {
            try (var output = Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) != -1) {
                    count += length;
                    if (count > maxBytes) throw new IOException("ARTIFACT_SIZE_LIMIT");
                    output.write(buffer, 0, length);
                    hash.update(buffer, 0, length);
                }
            }
            return new Metadata(key, count, "sha256:" + HexFormat.of().formatHex(hash.digest()));
        } catch (IOException e) {
            if (created) Files.deleteIfExists(target);
            throw e;
        }
    }

    @Override
    public byte[] read(String key, int maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("Invalid artifact size limit");
        Path target = resolve(key);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("ARTIFACT_NOT_FOUND");
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("ARTIFACT_SIZE_LIMIT");
            return bytes;
        }
    }

    @Override
    public void deleteTree(String prefix) throws IOException {
        Path target = resolve(prefix);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try (var files = Files.walk(target)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }

    private Path resolve(String key) throws IOException {
        if (key == null
                || key.isBlank()
                || key.length() > 4096
                || key.startsWith("/")
                || key.contains("\\")
                || key.contains(":")) throw new IllegalArgumentException("Invalid artifact key");
        for (String part : key.split("/", -1))
            if (part.isBlank()
                    || part.equals(".")
                    || part.equals("..")
                    || part.chars().anyMatch(c -> c < 32)) throw new IllegalArgumentException("Invalid artifact key");
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root) || target.equals(root))
            throw new IllegalArgumentException("Artifact outside root");
        Path current = target.getRoot();
        for (Path part : target) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)
                    || (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                            && Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS)
                                    .isOther())) throw new IOException("ARTIFACT_LINK_REJECTED");
        }
        return target;
    }
}
