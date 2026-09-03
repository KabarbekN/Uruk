package io.semanticmap.platform.analysis;

import java.util.Map;
import java.util.UUID;

public record TaskLease(
        UUID id,
        UUID organizationId,
        UUID projectId,
        UUID runId,
        String type,
        Map<String, Object> payload,
        String owner,
        UUID token,
        int attempt) {
    public UUID payloadId(String key) {
        return UUID.fromString(String.valueOf(payload.get(key)));
    }
}
