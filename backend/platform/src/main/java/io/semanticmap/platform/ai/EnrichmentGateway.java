package io.semanticmap.platform.ai;

public interface EnrichmentGateway {
    record Reply(String content, Integer inputTokens, Integer outputTokens) {}

    Reply enrich(EvidenceBundle bundle, Runnable beforeAttempt);
}
