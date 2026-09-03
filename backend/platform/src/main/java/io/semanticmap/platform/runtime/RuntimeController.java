package io.semanticmap.platform.runtime;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class RuntimeController {
    private final RuntimeService runtime;
    private final OtlpParser parser;

    public RuntimeController(RuntimeService runtime, OtlpParser parser) {
        this.runtime = runtime;
        this.parser = parser;
    }

    @PostMapping(value = "/api/v1/analysis-runs/{id}/runtime/traces", consumes = "application/json")
    public Map<String, Object> ingest(@PathVariable UUID id, HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > OtlpParser.MAX_BODY_BYTES)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "OTLP body exceeds 1 MiB");
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity"))
            throw new ResponseStatusException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only uncompressed OTLP JSON is supported");
        return runtime.ingest(id, parser.parse(request.getInputStream()));
    }

    @GetMapping("/api/v1/analysis-runs/{id}/runtime")
    public Map<String, Object> get(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return runtime.get(id, limit, offset);
    }
}
