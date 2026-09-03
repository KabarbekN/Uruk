package io.semanticmap.platform.storage;

import static org.assertj.core.api.Assertions.*;

import io.semanticmap.contract.Protocol;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileArtifactStoreTest {
    @TempDir
    Path root;

    @Test
    void immutableWriteRoundTripAndHash() throws Exception {
        var store = new LocalFileArtifactStore(root.toString());
        byte[] content = "artifact".getBytes(StandardCharsets.UTF_8);
        var metadata = store.write("run/manifest.json", new ByteArrayInputStream(content), 100);
        assertThat(metadata.sha256()).isEqualTo(Protocol.hash("artifact"));
        assertThat(metadata.bytes()).isEqualTo(content.length);
        assertThat(store.read(metadata.key(), 100)).isEqualTo(content);
        assertThatThrownBy(() -> store.write(metadata.key(), new ByteArrayInputStream(content), 100))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(store.read(metadata.key(), 100)).isEqualTo(content);
    }

    @Test
    void rejectsTraversalAndBoundsReadAndWrite() throws Exception {
        var store = new LocalFileArtifactStore(root.toString());
        for (String key :
                new String[] {"", "../outside", "/root", "C:/root", "run/../other", "run\\other", "run//file"})
            assertThatThrownBy(() -> store.deleteTree(key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write("run/large", new ByteArrayInputStream(new byte[20]), 10))
                .hasMessage("ARTIFACT_SIZE_LIMIT");
        assertThat(root.resolve("run/large")).doesNotExist();
        store.write("run/small", new ByteArrayInputStream(new byte[20]), 20);
        assertThatThrownBy(() -> store.read("run/small", 10)).hasMessage("ARTIFACT_SIZE_LIMIT");
    }

    @Test
    void deletesOnlyRequestedPrefixAndIsIdempotent() throws Exception {
        var store = new LocalFileArtifactStore(root.toString());
        Files.createDirectories(root.resolve("retained"));
        Files.createDirectories(root.resolve("expired/source"));
        store.deleteTree("expired");
        store.deleteTree("expired");
        assertThat(root.resolve("expired")).doesNotExist();
        assertThat(root.resolve("retained")).exists();
    }
}
