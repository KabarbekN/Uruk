package io.semanticmap.platform.ai;

import io.semanticmap.platform.settings.AiConfiguration;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Component
public class SpringAiGateway implements EnrichmentGateway {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SpringAiGateway.class);
    private final AiConfiguration config;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            0,
            4,
            30,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            Thread.ofPlatform().name("semantic-ai-", 0).daemon().factory());
    private volatile OpenAiChatModel model;
    private volatile String cachedModelName;
    private volatile String cachedBaseUrl;
    private int failures;
    private long openUntil;

    public SpringAiGateway(AiConfiguration config) {
        this.config = config;
    }

    @Override
    public Reply enrich(EvidenceBundle bundle, Runnable beforeAttempt) {
        synchronized (this) {
            if (openUntil > System.nanoTime())
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI circuit is open");
        }
        java.util.concurrent.Future<Reply> future;
        try {
            future = executor.submit(() -> call(bundle, beforeAttempt));
        } catch (RejectedExecutionException ex) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "AI concurrency limit reached");
        }
        try {
            Reply reply = future.get(300, TimeUnit.SECONDS);
            synchronized (this) {
                failures = 0;
                openUntil = 0;
            }
            return reply;
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            failed();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI request interrupted");
        } catch (TimeoutException ex) {
            future.cancel(true);
            failed();
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI provider timed out");
        } catch (ExecutionException ex) {
            failed();
            if (ex.getCause() instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI provider request failed");
        }
    }

    private Reply call(EvidenceBundle bundle, Runnable beforeAttempt) throws InterruptedException {
        OpenAiChatModel chat = model();
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            beforeAttempt.run();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                var response = chat.call(new Prompt(
                        List.of(new SystemMessage(EnrichmentValidator.PROMPT), new UserMessage(bundle.json()))));
                if (response == null
                        || response.getResult() == null
                        || response.getResult().getOutput() == null)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Malformed AI response");
                var metadata = response.getMetadata();
                var usage = metadata != null ? metadata.getUsage() : null;
                boolean empty = usage == null || usage instanceof EmptyUsage;
                Integer input = empty || usage.getPromptTokens() == null
                        ? null
                        : usage.getPromptTokens().intValue();
                Integer output = empty || usage.getCompletionTokens() == null
                        ? null
                        : usage.getCompletionTokens().intValue();
                return new Reply(response.getResult().getOutput().getText(), input, output);
            } catch (RuntimeException ex) {
                if (attempt == 0 && transientFailure(ex)) continue;
                if (ex instanceof ResponseStatusException status) throw status;
                log.error("AI gateway request execution failed: {}", ex.getMessage(), ex);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI request failed: " + ex.getMessage(), ex);
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI retry failed");
    }

    private static boolean transientFailure(RuntimeException error) {
        if (error instanceof TransientAiException) return true;
        if (!(error instanceof ResourceAccessException)) return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof javax.net.ssl.SSLException) return false;
            if (cause instanceof java.net.SocketTimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.ConnectException) return true;
        }
        return false;
    }

    private synchronized void failed() {
        if (++failures >= 5)
            openUntil = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    }

    private synchronized OpenAiChatModel model() {
        String curModel = config.model();
        String curBase = config.baseUrl();
        if (curBase.endsWith("/v1")) {
            curBase = curBase.substring(0, curBase.length() - 3);
        }
        if (model != null && curModel.equals(cachedModelName) && curBase.equals(cachedBaseUrl)) {
            return model;
        }
        var client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(90));
        var rest = RestClient.builder().requestFactory(factory).requestInterceptor((request, body, execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            try {
                byte[] bytes = response.getBody().readNBytes(131073);
                if (bytes.length > 131072) {
                    response.close();
                    throw new IllegalStateException("AI response exceeds limit");
                }
                return new BoundedResponse(response, bytes);
            } catch (IOException ex) {
                response.close();
                throw ex;
            }
        });
        var api = OpenAiApi.builder()
                .baseUrl(curBase)
                .apiKey(config.apiKey())
                .restClientBuilder(rest)
                .build();
        var format = ResponseFormat.builder()
                .type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder()
                        .name("semantic_enrichment")
                        .schema(EnrichmentValidator.SCHEMA)
                        .strict(true)
                        .build())
                .build();
        model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(curModel)
                        .temperature(0.0)
                        .maxTokens(2048)
                        .responseFormat(format)
                        .internalToolExecutionEnabled(false)
                        .store(false)
                        .build())
                .retryTemplate(RetryTemplate.builder().maxAttempts(1).build())
                .build();
        cachedModelName = curModel;
        cachedBaseUrl = curBase;
        return model;
    }

    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private record BoundedResponse(ClientHttpResponse delegate, byte[] bytes) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
