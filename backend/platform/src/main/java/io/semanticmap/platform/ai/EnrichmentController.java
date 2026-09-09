package io.semanticmap.platform.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class EnrichmentController {
    private final EnrichmentService enrichment;

    public EnrichmentController(EnrichmentService enrichment) {
        this.enrichment = enrichment;
    }

    @PostMapping("/api/v1/semantic/nodes/{nodeId}/enrich")
    public Map<String, Object> enrich(@PathVariable UUID nodeId) {
        return enrichment.enrich(nodeId);
    }

    @PostMapping("/api/v1/analysis-runs/{runId}/enrich-all")
    public Map<String, Object> enrichAll(@PathVariable UUID runId) {
        return enrichment.enrichAll(runId);
    }

    @PostMapping("/api/v1/analysis-runs/{runId}/controllers/{controllerKey}/enrich")
    public Map<String, Object> enrichController(
            @PathVariable UUID runId, @PathVariable String controllerKey) {
        return enrichment.enrichController(runId, controllerKey);
    }

    @PostMapping("/api/v1/analysis-runs/{runId}/scenarios/{endpointId}/enrich-pipeline")
    public Map<String, Object> enrichPipeline(
            @PathVariable UUID runId, @PathVariable UUID endpointId) {
        return enrichment.enrichPipeline(runId, endpointId);
    }

    @PostMapping("/api/v1/analysis-runs/{runId}/cancel-enrichment")
    public Map<String, Object> cancelEnrichment(@PathVariable UUID runId) {
        return enrichment.cancelEnrichment(runId);
    }

    @GetMapping("/api/v1/analysis-runs/{runId}/enrichment-progress")
    public Map<String, Object> progress(@PathVariable UUID runId) {
        return enrichment.getProgress(runId);
    }

    @PostMapping("/api/v1/analysis-runs/{runId}/semantic-search")
    public List<Map<String, Object>> semanticSearch(
            @PathVariable UUID runId, @RequestBody(required = false) Map<String, Object> body, @RequestParam(required = false) String q) {
        String query = "";
        if (body != null && body.containsKey("query")) {
            query = body.get("query").toString();
        } else if (q != null) {
            query = q;
        }
        return enrichment.semanticSearch(runId, query);
    }
}

