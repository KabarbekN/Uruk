package io.semanticmap.platform.graph;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class GraphController {
    private final GraphQueries queries;

    public GraphController(GraphQueries queries) {
        this.queries = queries;
    }

    @GetMapping("/analysis-runs/{id}/canvas")
    public Map<String, Object> canvas(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "BUSINESS") GraphQueries.View view,
            @RequestParam(defaultValue = "2") int depth,
            @RequestParam(required = false) UUID rootNodeId,
            @RequestParam(defaultValue = "0") double minConfidence,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "false") boolean onlyChanged,
            @RequestParam(defaultValue = "2") int levelOfDetail,
            @RequestParam(required = false) String analyzer,
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String framework,
            @RequestParam(required = false) String sourceLayer,
            @RequestParam(required = false) String analyzerId,
            @RequestParam(required = false) String componentId) {
        return queries.canvas(
                id,
                view,
                depth,
                rootNodeId,
                minConfidence,
                search,
                onlyChanged,
                levelOfDetail,
                new GraphQueries.Filters(
                        analyzer == null ? analyzerId : analyzer,
                        component == null ? componentId : component,
                        framework,
                        sourceLayer));
    }

    @GetMapping("/analysis-runs/{id}/controllers")
    public List<Map<String, Object>> controllers(@PathVariable UUID id) {
        return queries.controllers(id);
    }

    @GetMapping("/semantic/nodes/{id}")
    public Map<String, Object> node(@PathVariable UUID id) {
        return queries.node(id);
    }

    @GetMapping("/semantic/nodes/{id}/neighbors")
    public Map<String, Object> neighbors(@PathVariable UUID id, @RequestParam(defaultValue = "1") int depth) {
        return queries.neighbors(id, depth);
    }

    @GetMapping("/semantic/nodes/{id}/evidence")
    public List<Map<String, Object>> nodeEvidence(@PathVariable UUID id) {
        return queries.nodeEvidence(id);
    }

    @GetMapping("/semantic/edges/{id}")
    public Map<String, Object> edge(@PathVariable UUID id) {
        return queries.edge(id);
    }

    @GetMapping("/semantic/edges/{id}/evidence")
    public List<Map<String, Object>> edgeEvidence(@PathVariable UUID id) {
        return queries.edgeEvidence(id);
    }

    @GetMapping("/projects/{id}/semantic-diff")
    public List<Map<String, Object>> diff(
            @PathVariable UUID id, @RequestParam UUID fromAnalysisRun, @RequestParam UUID toAnalysisRun) {
        return queries.diff(id, fromAnalysisRun, toAnalysisRun);
    }

    @GetMapping("/analysis-runs/{id}/quarantined-facts")
    public List<Map<String, Object>> quarantine(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queries.quarantine(id, limit, offset);
    }

    @GetMapping("/analysis-runs/{id}/unresolved-symbols")
    public List<Map<String, Object>> unresolved(@PathVariable UUID id, @RequestParam(defaultValue = "200") int limit) {
        return queries.unresolved(id, limit);
    }

    @GetMapping("/projects/{id}/review-queue")
    public List<Map<String, Object>> reviewQueue(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID analysisRunId,
            @RequestParam(defaultValue = "200") int limit) {
        return queries.reviewQueue(id, analysisRunId, limit);
    }

    @GetMapping("/projects/{id}/search")
    public List<Map<String, Object>> search(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) UUID analysisRunId,
            @RequestParam(defaultValue = "100") int limit) {
        return queries.search(id, analysisRunId, q, limit);
    }
}
