package io.semanticmap.platform.ai;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
}
