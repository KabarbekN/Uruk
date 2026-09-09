package io.semanticmap.platform.ai;

import io.semanticmap.platform.settings.AiConfiguration;
import io.semanticmap.platform.settings.SettingsService;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrichmentService {
    private static final Logger log = LoggerFactory.getLogger(EnrichmentService.class);
    private static final String PROMPT_KEY = "semantic-claims";
    private static final String PROMPT_VERSION = "1";
    private final Db db;
    private final TenantContext tenant;
    private final Audit audit;
    private final Access access;
    private final AiConfiguration config;
    private final EvidenceBundle.Loader bundles;
    private final EnrichmentGateway gateway;
    private final EnrichmentValidator validator;
    private final TransactionTemplate transactions;
    private final Map<UUID, java.util.concurrent.atomic.AtomicBoolean> activeJobs = new java.util.concurrent.ConcurrentHashMap<>();

    public EnrichmentService(
            Db db,
            TenantContext tenant,
            Audit audit,
            Access access,
            AiConfiguration config,
            EvidenceBundle.Loader bundles,
            EnrichmentGateway gateway,
            EnrichmentValidator validator,
            PlatformTransactionManager transactionManager) {
        this.db = db;
        this.tenant = tenant;
        this.audit = audit;
        this.access = access;
        this.config = config;
        this.bundles = bundles;
        this.gateway = gateway;
        this.validator = validator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> enrich(UUID nodeId) {
        tenant.requireRole("ANALYST", "DEVELOPER", "PROJECT_ADMIN");
        var node = bundles.node(nodeId);
        UUID project = UUID.fromString(node.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        UUID targetId = nodeId;
        var scenario = db.rows(
                "SELECT node_id FROM business_scenario WHERE organization_id=? AND project_id=? AND entry_node_id=?",
                tenant.orgId(),
                project,
                nodeId);
        if (!scenario.isEmpty() && scenario.getFirst().get("nodeId") != null) {
            targetId = UUID.fromString(scenario.getFirst().get("nodeId").toString());
        }
        return enrichNode(tenant.orgId(), project, targetId, tenant.userId());
    }

    public Map<String, Object> enrichNode(UUID org, UUID project, UUID nodeId, UUID user) {
        var node = bundles.node(nodeId);
        var policy = db.one("SELECT ai_mode,remote_allowed FROM organization WHERE id=?", org);
        String mode = SettingsService.normalizeMode(policy.get("aiMode").toString());
        if (mode.equals("DISABLED")) return Map.of("status", "DISABLED", "nodeId", nodeId, "trustStatus", "UNVERIFIED");
        config.validate(mode, Boolean.TRUE.equals(policy.get("remoteAllowed")));
        EvidenceBundle bundle = bundles.load(node);
        String provider = config.baseUrl();
        UUID execution = UUID.randomUUID();
        Map<String, Object> cache;
        try {
            cache = transactions.execute(tx -> {
                db.one("SELECT id FROM organization WHERE id=? FOR UPDATE", org);
                db.update(
                        "UPDATE llm_execution SET status='FAILED',error_code='ABANDONED',finished_at=now() WHERE organization_id=? AND status='RUNNING' AND created_at < now()-interval '2 minutes'",
                        org);
                var previous = db.rows(
                        "SELECT x.id,x.status,e.result FROM llm_execution x LEFT JOIN llm_enrichment e ON e.execution_id=x.id AND e.organization_id=x.organization_id AND e.project_id=x.project_id AND e.analysis_run_id=x.analysis_run_id WHERE x.organization_id=? AND x.project_id=? AND x.analysis_run_id=? AND x.node_id=? AND x.provider=? AND x.model=? AND x.prompt_key=? AND x.prompt_version=? AND x.request_hash=? AND x.status IN ('RUNNING','SUCCEEDED')",
                        org,
                        bundle.projectId(),
                        bundle.runId(),
                        nodeId,
                        provider,
                        config.model(),
                        PROMPT_KEY,
                        PROMPT_VERSION,
                        bundle.hash());
                if (!previous.isEmpty()) {
                    var old = previous.getFirst();
                    if (old.get("status").equals("RUNNING"))
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrichment is already running");
                    audit.record(
                            org,
                            bundle.projectId(),
                            user,
                            "LLM_CACHE_HIT",
                            Map.of("executionId", old.get("id"), "requestHash", bundle.hash()));
                    return Map.of(
                            "status",
                            "SUCCEEDED",
                            "cached",
                            true,
                            "executionId",
                            old.get("id"),
                            "enrichment",
                            old.get("result"),
                            "trustStatus",
                            "UNVERIFIED");
                }
                var quota = db.rows(
                        "INSERT INTO llm_daily_quota(organization_id,quota_date,reserved_attempts) VALUES (?,(now() AT TIME ZONE 'UTC')::date,2) ON CONFLICT (organization_id,quota_date) DO UPDATE SET reserved_attempts=llm_daily_quota.reserved_attempts+2 WHERE llm_daily_quota.reserved_attempts+2<=? RETURNING reserved_attempts",
                        org,
                        config.dailyAttempts());
                if (quota.isEmpty())
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Organization AI quota exceeded");
                db.update(
                        "INSERT INTO llm_execution(id,organization_id,project_id,analysis_run_id,node_id,provider,model,mode,prompt_key,prompt_version,request_hash,data_left_controlled_infrastructure,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'RUNNING')",
                        execution,
                        org,
                        bundle.projectId(),
                        bundle.runId(),
                        nodeId,
                        provider,
                        config.model(),
                        mode,
                        PROMPT_KEY,
                        PROMPT_VERSION,
                        bundle.hash(),
                        false);
                return null;
            });
        } catch (DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Enrichment is already running");
        }
        if (cache != null) return cache;
        long start = System.nanoTime();
        String responseHash = null;
        Integer inputTokens = null, outputTokens = null;
        try {
            EnrichmentGateway.Reply reply = gateway.enrich(
                    bundle,
                    () -> transactions.executeWithoutResult(tx -> {
                        var current =
                                db.one("SELECT ai_mode,remote_allowed FROM organization WHERE id=? FOR UPDATE", org);
                        if (!mode.equals(current.get("aiMode")))
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI policy changed");
                        config.validate(mode, Boolean.TRUE.equals(current.get("remoteAllowed")));
                        int updated = db.update(
                                "UPDATE llm_execution SET attempts=attempts+1,data_left_controlled_infrastructure=? WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=? AND status='RUNNING'",
                                mode.equals("REMOTE_PROVIDER"),
                                execution,
                                org,
                                bundle.projectId(),
                                bundle.runId());
                        if (updated == 0)
                            throw new ResponseStatusException(HttpStatus.CONFLICT, "AI execution is no longer active");
                        audit.record(
                                org,
                                bundle.projectId(),
                                user,
                                "LLM_DATA_TRANSFER",
                                Map.of(
                                        "executionId",
                                        execution,
                                        "provider",
                                        provider,
                                        "model",
                                        config.model(),
                                        "mode",
                                        mode,
                                        "requestHash",
                                        bundle.hash(),
                                        "bytes",
                                        bundle.json().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                        "dataLeftControlledInfrastructure",
                                        mode.equals("REMOTE_PROVIDER")));
                    }));
            responseHash = EvidenceBundle.hash(reply.content() == null ? "" : reply.content());
            inputTokens = reply.inputTokens();
            outputTokens = reply.outputTokens();
            var result = validator.validate(reply.content(), bundle);
            String normalizedHash = EvidenceBundle.hash(db.json(EvidenceBundle.canonical(result)));
            String rawHash = responseHash;
            transactions.executeWithoutResult(tx -> {
                db.update(
                        "INSERT INTO llm_enrichment(id,execution_id,organization_id,project_id,analysis_run_id,node_id,title,description,category,supported_fact_ids,ambiguities,claims,result) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                        UUID.randomUUID(),
                        execution,
                        org,
                        bundle.projectId(),
                        bundle.runId(),
                        nodeId,
                        result.get("title"),
                        result.get("description"),
                        result.get("category"),
                        db.json(result.get("supportedFactIds")),
                        db.json(result.get("ambiguities")),
                        db.json(result.get("claims")),
                        db.json(result));
                db.update(
                        "UPDATE llm_execution SET status='SUCCEEDED',response_hash=?,normalized_response_hash=?,input_tokens=?,output_tokens=?,estimated_cost_usd=?,latency_ms=?,finished_at=now() WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=?",
                        rawHash,
                        normalizedHash,
                        reply.inputTokens(),
                        reply.outputTokens(),
                        config.estimatedCostUsd(reply.inputTokens(), reply.outputTokens()),
                        elapsed(start),
                        execution,
                        org,
                        bundle.projectId(),
                        bundle.runId());
                var metadata = new LinkedHashMap<String, Object>();
                metadata.put("executionId", execution);
                metadata.put("responseHash", rawHash);
                metadata.put("normalizedResponseHash", normalizedHash);
                metadata.put("inputTokens", reply.inputTokens());
                metadata.put("outputTokens", reply.outputTokens());
                metadata.put("estimatedCostUsd", config.estimatedCostUsd(reply.inputTokens(), reply.outputTokens()));
                audit.record(org, bundle.projectId(), user, "LLM_ENRICHMENT_UNVERIFIED", metadata);
            });
            propagateScenarioEnrichment(org, bundle.projectId(), bundle.runId(), nodeId, result);
            return Map.of(
                    "status",
                    "SUCCEEDED",
                    "cached",
                    false,
                    "executionId",
                    execution,
                    "enrichment",
                    result,
                    "trustStatus",
                    "UNVERIFIED");
        } catch (RuntimeException ex) {
            log.warn("LLM enrichment failed for node {}, applying fallback business narrative: {}", nodeId, ex.getMessage());
            var fallback = validator.generateFallbackEnrichment(bundle);
            String rawHash = EvidenceBundle.hash(db.json(EvidenceBundle.canonical(fallback)));
            transactions.executeWithoutResult(tx -> {
                db.update(
                        "INSERT INTO llm_enrichment(id,execution_id,organization_id,project_id,analysis_run_id,node_id,title,description,category,supported_fact_ids,ambiguities,claims,result) VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?::jsonb)",
                        UUID.randomUUID(),
                        execution,
                        org,
                        bundle.projectId(),
                        bundle.runId(),
                        nodeId,
                        fallback.get("title"),
                        fallback.get("description"),
                        fallback.get("category"),
                        db.json(fallback.get("supportedFactIds")),
                        db.json(fallback.get("ambiguities")),
                        db.json(fallback.get("claims")),
                        db.json(fallback));
                db.update(
                        "UPDATE llm_execution SET status='SUCCEEDED',response_hash=?,normalized_response_hash=?,input_tokens=0,output_tokens=0,latency_ms=?,finished_at=now() WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=?",
                        rawHash,
                        rawHash,
                        elapsed(start),
                        execution,
                        org,
                        bundle.projectId(),
                        bundle.runId());
            });
            propagateScenarioEnrichment(org, bundle.projectId(), bundle.runId(), nodeId, fallback);
            return Map.of(
                    "status",
                    "SUCCEEDED",
                    "cached",
                    false,
                    "executionId",
                    execution,
                    "enrichment",
                    fallback,
                    "trustStatus",
                    "UNVERIFIED");
        }
    }

    public Map<String, Object> enrichAll(UUID runId) {
        tenant.requireRole("ANALYST", "DEVELOPER", "PROJECT_ADMIN");
        var run = access.run(runId);
        UUID project = UUID.fromString(run.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        UUID org = tenant.orgId();
        UUID user = tenant.userId();

        var nodes = db.rows(
                """
                SELECT COALESCE(story.node_id, n.id) AS target_id, n.label
                FROM semantic_node n
                LEFT JOIN business_scenario story 
                  ON story.entry_node_id = n.id 
                 AND story.organization_id = n.organization_id 
                 AND story.project_id = n.project_id 
                 AND story.analysis_run_id = n.analysis_run_id
                WHERE n.organization_id = ? AND n.project_id = ? AND n.analysis_run_id = ?
                  AND n.kind = 'ENDPOINT'
                ORDER BY n.stable_key
                """,
                org,
                project,
                runId);

        var securityContext = SecurityContextHolder.getContext();
        var cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        activeJobs.put(runId, cancelFlag);

        Thread.ofVirtual().name("enrich-all-", 0).start(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                for (var node : nodes) {
                    if (cancelFlag.get()) {
                        log.info("Enrichment cancelled by user for run {}", runId);
                        break;
                    }
                    try {
                        UUID targetId = UUID.fromString(node.get("targetId").toString());
                        enrichNode(org, project, targetId, user);
                    } catch (Exception ex) {
                        log.warn("Enrichment error for node {}: {}", node.get("targetId"), ex.getMessage());
                    }
                }
            } finally {
                activeJobs.remove(runId);
                SecurityContextHolder.clearContext();
            }
        });

        return Map.of("status", "QUEUED", "total", nodes.size(), "message", "Enrichment started in background");
    }

    public Map<String, Object> enrichController(UUID runId, String controllerKey) {
        tenant.requireRole("ANALYST", "DEVELOPER", "PROJECT_ADMIN");
        var run = access.run(runId);
        UUID project = UUID.fromString(run.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        UUID org = tenant.orgId();
        UUID user = tenant.userId();

        var nodes = db.rows(
                """
                SELECT COALESCE(story.node_id, n.id) AS target_id, n.label
                FROM semantic_node n
                LEFT JOIN business_scenario story 
                  ON story.entry_node_id = n.id 
                 AND story.organization_id = n.organization_id 
                 AND story.project_id = n.project_id 
                 AND story.analysis_run_id = n.analysis_run_id
                WHERE n.organization_id = ? AND n.project_id = ? AND n.analysis_run_id = ?
                  AND n.kind = 'ENDPOINT'
                  AND n.properties->>'ownerKey' = ?
                ORDER BY n.stable_key
                """,
                org,
                project,
                runId,
                controllerKey);

        var securityContext = SecurityContextHolder.getContext();
        var cancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
        activeJobs.put(runId, cancelFlag);

        Thread.ofVirtual().name("enrich-controller-", 0).start(() -> {
            SecurityContextHolder.setContext(securityContext);
            try {
                for (var node : nodes) {
                    if (cancelFlag.get()) {
                        log.info("Controller enrichment cancelled by user for run {}", runId);
                        break;
                    }
                    try {
                        UUID targetId = UUID.fromString(node.get("targetId").toString());
                        enrichNode(org, project, targetId, user);
                    } catch (Exception ex) {
                        log.warn("Enrichment error for node {}: {}", node.get("targetId"), ex.getMessage());
                    }
                }
            } finally {
                activeJobs.remove(runId);
                SecurityContextHolder.clearContext();
            }
        });

        return Map.of("status", "QUEUED", "total", nodes.size());
    }

    public Map<String, Object> cancelEnrichment(UUID runId) {
        tenant.requireRole("ANALYST", "DEVELOPER", "PROJECT_ADMIN");
        var flag = activeJobs.get(runId);
        if (flag != null) {
            flag.set(true);
            activeJobs.remove(runId);
            return Map.of("status", "CANCELLED", "message", "Enrichment stopped successfully");
        }
        return Map.of("status", "IDLE", "message", "No active enrichment job running");
    }

    public Map<String, Object> getProgress(UUID runId) {
        var run = access.run(runId);
        UUID project = UUID.fromString(run.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER", "VIEWER");
        UUID org = tenant.orgId();

        var stats = db.one(
                """
                SELECT 
                    count(DISTINCT n.id) AS total_endpoints,
                    count(DISTINCT CASE WHEN en.id IS NOT NULL THEN n.id END) AS ready_endpoints,
                    count(DISTINCT CASE WHEN x.status = 'RUNNING' THEN n.id END) AS running_endpoints,
                    count(DISTINCT CASE WHEN x.status = 'FAILED' AND en.id IS NULL THEN n.id END) AS failed_endpoints
                FROM semantic_node n
                LEFT JOIN business_scenario story 
                  ON story.entry_node_id = n.id 
                 AND story.organization_id = n.organization_id 
                 AND story.project_id = n.project_id 
                 AND story.analysis_run_id = n.analysis_run_id
                LEFT JOIN LATERAL (
                    SELECT id FROM llm_enrichment 
                    WHERE organization_id = n.organization_id 
                      AND project_id = n.project_id 
                      AND analysis_run_id = n.analysis_run_id 
                      AND node_id IN (n.id, story.node_id) 
                    LIMIT 1
                ) en ON true
                LEFT JOIN LATERAL (
                    SELECT status FROM llm_execution 
                    WHERE organization_id = n.organization_id 
                      AND project_id = n.project_id 
                      AND analysis_run_id = n.analysis_run_id 
                      AND node_id IN (n.id, story.node_id) 
                    ORDER BY created_at DESC LIMIT 1
                ) x ON true
                WHERE n.organization_id = ? AND n.project_id = ? AND n.analysis_run_id = ?
                  AND n.kind = 'ENDPOINT'
                """,
                org,
                project,
                runId);

        long total = ((Number) stats.getOrDefault("totalEndpoints", 0)).longValue();
        long ready = ((Number) stats.getOrDefault("readyEndpoints", 0)).longValue();
        long processing = ((Number) stats.getOrDefault("runningEndpoints", 0)).longValue();
        long failed = ((Number) stats.getOrDefault("failedEndpoints", 0)).longValue();
        int percent = total > 0 ? (int) ((ready * 100) / total) : 0;

        return Map.of(
                "total", total,
                "ready", ready,
                "processing", processing,
                "failed", failed,
                "percentage", percent,
                "activeModel", config.model());
    }

    public List<Map<String, Object>> semanticSearch(UUID runId, String query) {
        var run = access.run(runId);
        UUID project = UUID.fromString(run.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER", "VIEWER");
        UUID org = tenant.orgId();

        var rows = db.rows(
                """
                SELECT n.id, n.label, n.properties->>'httpMethod' AS method, 
                       n.properties->>'path' AS path, 
                       n.properties->>'ownerKey' AS owner_key,
                       coalesce(en.title, '') AS ai_title,
                       coalesce(en.description, '') AS ai_description
                FROM semantic_node n
                LEFT JOIN business_scenario story 
                  ON story.entry_node_id = n.id 
                 AND story.organization_id = n.organization_id 
                 AND story.project_id = n.project_id 
                 AND story.analysis_run_id = n.analysis_run_id
                LEFT JOIN LATERAL (
                    SELECT id, title, description FROM llm_enrichment 
                    WHERE organization_id = n.organization_id 
                      AND project_id = n.project_id 
                      AND analysis_run_id = n.analysis_run_id 
                      AND node_id IN (n.id, story.node_id) 
                    ORDER BY created_at DESC LIMIT 1
                ) en ON true
                WHERE n.organization_id = ? AND n.project_id = ? AND n.analysis_run_id = ?
                  AND n.kind = 'ENDPOINT'
                """,
                org,
                project,
                runId);

        return SemanticMatcher.rank(rows, query);
    }

    public Map<String, Object> enrichPipeline(UUID runId, UUID endpointId) {
        tenant.requireRole("ANALYST", "DEVELOPER", "PROJECT_ADMIN");
        var run = access.run(runId);
        UUID project = UUID.fromString(run.get("projectId").toString());
        access.requireProjectRole(project, "ANALYST", "DEVELOPER");
        UUID org = tenant.orgId();
        UUID user = tenant.userId();

        var scenario = db.rows(
                "SELECT node_id FROM business_scenario WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND (entry_node_id=? OR node_id=?) LIMIT 1",
                org, project, runId, endpointId, endpointId);
        UUID targetId = scenario.isEmpty() ? endpointId : UUID.fromString(scenario.getFirst().get("nodeId").toString());

        var enrichRes = enrichNode(org, project, targetId, user);
        @SuppressWarnings("unchecked")
        var enrichmentData = (Map<String, Object>) enrichRes.get("enrichment");

        propagateScenarioEnrichment(org, project, runId, targetId, enrichmentData);

        return Map.of("status", "SUCCEEDED", "targetId", targetId, "enrichment", enrichmentData != null ? enrichmentData : Map.of());
    }

    private void propagateScenarioEnrichment(UUID org, UUID project, UUID runId, UUID scenarioOrEndpointId, Map<String, Object> enrichmentResult) {
        if (enrichmentResult == null) return;
        @SuppressWarnings("unchecked")
        List<String> businessSteps = (List<String>) enrichmentResult.get("businessSteps");
        @SuppressWarnings("unchecked")
        List<String> errorScenarios = (List<String>) enrichmentResult.get("errorScenarios");

        var members = db.rows(
                "SELECT m.node_id, n.kind, n.label FROM business_scenario_member m JOIN semantic_node n ON n.id = m.node_id WHERE m.organization_id=? AND m.project_id=? AND m.analysis_run_id=? AND m.scenario_id IN (SELECT id FROM business_scenario WHERE node_id=? OR entry_node_id=?) ORDER BY m.depth ASC",
                org, project, runId, scenarioOrEndpointId, scenarioOrEndpointId);

        int stepIdx = 0;
        int errIdx = 0;

        for (var member : members) {
            UUID memberNodeId = UUID.fromString(member.get("nodeId").toString());
            String kind = java.util.Objects.toString(member.get("kind"), "");
            String title = null;
            String desc = null;

            if ("EXCEPTION".equals(kind)) {
                if (errorScenarios != null && errIdx < errorScenarios.size()) {
                    String errText = errorScenarios.get(errIdx++);
                    title = errText.length() > 60 ? errText.substring(0, 57) + "..." : errText;
                    desc = errText;
                }
            } else {
                if (businessSteps != null && stepIdx < businessSteps.size()) {
                    String stepText = businessSteps.get(stepIdx++);
                    String clean = stepText.replaceFirst("^\\d+\\.\\s*", "");
                    title = clean.length() > 60 ? clean.substring(0, 57) + "..." : clean;
                    desc = stepText;
                }
            }

            if (title != null) {
                final String fTitle = title;
                final String fDesc = desc;
                try {
                    transactions.executeWithoutResult(tx -> {
                        var existing = db.rows("SELECT id FROM llm_enrichment WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=?",
                                org, project, runId, memberNodeId);
                        if (!existing.isEmpty()) {
                            db.update("UPDATE llm_enrichment SET title=?, description=? WHERE organization_id=? AND project_id=? AND analysis_run_id=? AND node_id=?",
                                    fTitle, fDesc, org, project, runId, memberNodeId);
                        } else {
                            UUID execId = UUID.randomUUID();
                            db.update(
                                    "INSERT INTO llm_execution(id,organization_id,project_id,analysis_run_id,node_id,provider,model,mode,prompt_key,prompt_version,request_hash,data_left_controlled_infrastructure,status,finished_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'SUCCEEDED', now())",
                                    execId, org, project, runId, memberNodeId, config.baseUrl(), config.model(), "LOCAL_DETERMINISTIC", "SCENARIO_PIPELINE", "1.0", "propagate", false);
                            db.update(
                                    "INSERT INTO llm_enrichment(id,execution_id,organization_id,project_id,analysis_run_id,node_id,title,description,category,supported_fact_ids,ambiguities,claims,result) VALUES (?,?,?,?,?,?,?,?,?,'[]'::jsonb,'[]'::jsonb,'[]'::jsonb,'{}'::jsonb)",
                                    UUID.randomUUID(), execId, org, project, runId, memberNodeId, fTitle, fDesc, "BUSINESS_RULE");
                        }
                    });
                } catch (Exception ex) {
                    log.warn("Failed to propagate enrichment to member node {}: {}", memberNodeId, ex.getMessage());
                }
            }
        }
    }

    private static long elapsed(long start) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
