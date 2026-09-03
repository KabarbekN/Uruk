package io.semanticmap.platform.runner;

import io.semanticmap.contract.Protocol;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

public interface AnalyzerExecutionPort {
    record Command(
            UUID executionId,
            UUID attemptId,
            String analyzerKey,
            String version,
            String imageReference,
            Path workspace,
            Path directory,
            Protocol.Request request,
            BooleanSupplier cancelled) {}

    record Result(
            String status,
            String imageDigest,
            Path output,
            Map<String, Object> coverage,
            List<Map<String, Object>> diagnostics) {}

    Result execute(Command command) throws IOException;

    void cancel(UUID executionId) throws IOException;

    record RunningExecution(UUID executionId, UUID attemptId) {}

    List<RunningExecution> running() throws IOException;

    void cancelAttempt(RunningExecution execution) throws IOException;
}
