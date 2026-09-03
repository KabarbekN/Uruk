package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.runner.BoundedProcess;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.runner.mode", havingValue = "kubernetes")
public class KubectlProcess {
    @FunctionalInterface
    interface Executor {
        BoundedProcess.Result run(List<String> args, Duration timeout, long limit, BooleanSupplier cancelled)
                throws IOException;
    }

    private final KubernetesSettings settings;
    private final Executor executor;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(64)
                            .maxStringLength(1048576)
                            .build())
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    @Autowired
    public KubectlProcess(KubernetesSettings settings) {
        this(
                settings,
                (args, timeout, limit, cancelled) ->
                        BoundedProcess.run(args, null, Map.of(), timeout, limit, cancelled));
    }

    KubectlProcess(KubernetesSettings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
    }

    public byte[] execute(List<String> arguments, Duration timeout, long limit, BooleanSupplier cancelled)
            throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("PROCESS_CANCELLED");
        var args = new ArrayList<>(settings.commandPrefix());
        args.addAll(arguments);
        var result = executor.run(List.copyOf(args), timeout, limit, cancelled);
        if (result.exitCode() != 0) throw new IOException("KUBERNETES_COMMAND_FAILED");
        if (result.stdout().length > limit) throw new IOException("KUBERNETES_RESPONSE_LIMIT");
        return result.stdout();
    }

    public JsonNode json(List<String> arguments, Duration timeout, BooleanSupplier cancelled) throws IOException {
        byte[] bytes = execute(arguments, timeout, 1048576, cancelled);
        if (bytes.length == 0) return json.missingNode();
        try {
            JsonNode result = json.readTree(bytes);
            if (result == null || !result.isObject()) throw new IOException("KUBERNETES_INVALID_RESPONSE");
            return result;
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IOException("KUBERNETES_INVALID_RESPONSE");
        }
    }
}
