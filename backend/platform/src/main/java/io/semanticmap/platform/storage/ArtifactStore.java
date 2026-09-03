package io.semanticmap.platform.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ArtifactStore {
    record Metadata(String key, long bytes, String sha256) {}

    Metadata write(String key, InputStream source, long maxBytes) throws IOException;

    byte[] read(String key, int maxBytes) throws IOException;

    void deleteTree(String prefix) throws IOException;
}
