package io.semanticmap.platform.ai;

import io.semanticmap.platform.settings.AiConfiguration;
import io.semanticmap.platform.settings.SettingsService;
import io.semanticmap.platform.shared.Access;
import io.semanticmap.platform.shared.Audit;
import io.semanticmap.platform.shared.Db;
import io.semanticmap.platform.shared.TenantContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EnrichmentService {
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
        access.requireProjectRole(UUID.fromString(node.get("projectId").toString()), "ANALYST", "DEVELOPER");
        UUID org = tenant.orgId(), user = tenant.userId();
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
            boolean rejected = ex instanceof IllegalArgumentException;
            String code = rejected ? "ENRICHMENT_VALIDATION_FAILED" : "PROVIDER_REQUEST_FAILED";
            db.update(
                    "UPDATE llm_execution SET status=?,error_code=?,response_hash=?,input_tokens=?,output_tokens=?,latency_ms=?,finished_at=now() WHERE id=? AND organization_id=? AND project_id=? AND analysis_run_id=? AND status='RUNNING'",
                    rejected ? "REJECTED" : "FAILED",
                    code,
                    responseHash,
                    inputTokens,
                    outputTokens,
                    elapsed(start),
                    execution,
                    org,
                    bundle.projectId(),
                    bundle.runId());
            audit.record(
                    org,
                    bundle.projectId(),
                    user,
                    "LLM_ENRICHMENT_FAILED",
                    Map.of("executionId", execution, "errorCode", code));
            if (ex instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(
                    rejected ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_GATEWAY, code);
        }
    }

    private static long elapsed(long start) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
