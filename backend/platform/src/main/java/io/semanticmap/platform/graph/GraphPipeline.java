package io.semanticmap.platform.graph;

import io.semanticmap.platform.graph.internal.FactIngestion;
import io.semanticmap.platform.graph.internal.GraphBuilder;
import io.semanticmap.platform.graph.internal.SemanticDiffer;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public module boundary used by analysis orchestration. */
@Service
public class GraphPipeline {
    private final FactIngestion ingestion;
    private final GraphBuilder builder;
    private final SemanticDiffer differ;

    public GraphPipeline(FactIngestion ingestion, GraphBuilder builder, SemanticDiffer differ) {
        this.ingestion = ingestion;
        this.builder = builder;
        this.differ = differ;
    }

    @Transactional
    public void ingest(UUID org, UUID project, UUID revision, UUID run, UUID execution, Path workspace, Path output) {
        ingestion.ingest(org, project, revision, run, execution, workspace, output);
    }

    @Transactional
    public void build(UUID org, UUID project, UUID revision, UUID run) {
        builder.build(org, project, revision, run);
    }

    @Transactional
    public void diff(UUID org, UUID project, UUID from, UUID to) {
        differ.diff(org, project, from, to);
    }
}
