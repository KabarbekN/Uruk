package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.runner.AnalyzerOutputBudget;
import io.semanticmap.platform.runner.AnalyzerOutputValidator;
import io.semanticmap.platform.runner.RunnerPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "semantic.runner.mode", havingValue = "kubernetes")
public class KubernetesAnalyzerExecutionAdapter implements AnalyzerExecutionPort {
    private final KubernetesSettings settings;
    private final KubectlProcess kubectl;
    private final ObjectMapper mapper;
    private final AnalyzerOutputValidator validator;
    private final KubernetesManifests manifests;
    private final NetworkPolicyVerifier network;
    private final ConcurrentHashMap<RunningExecution, AtomicBoolean> active = new ConcurrentHashMap<>();

    public KubernetesAnalyzerExecutionAdapter(
            KubernetesSettings settings,
            KubectlProcess kubectl,
            ObjectMapper mapper,
            AnalyzerOutputValidator validator) {
        this.settings = settings;
        this.kubectl = kubectl;
        this.mapper = mapper;
        this.validator = validator;
        manifests = new KubernetesManifests(settings, mapper);
        network = new NetworkPolicyVerifier(settings);
    }

    @Override
    public Result execute(Command command) throws IOException {
        settings.validate(command);
        var execution = new RunningExecution(command.executionId(), command.attemptId());
        AtomicBoolean stop = new AtomicBoolean();
        if (active.putIfAbsent(execution, stop) != null) throw new IOException("KUBERNETES_ATTEMPT_ALREADY_RUNNING");
        BooleanSupplier cancelled = () -> stop.get() || command.cancelled().getAsBoolean();
        String name = KubernetesManifests.jobName(command.executionId(), command.attemptId());
        long deadline = System.nanoTime()
                + Duration.ofSeconds(settings.timeout(command)).toNanos();
        boolean submitted = false;
        String jobUid = null;
        Throwable failure = null;
        try {
            checkCancelled(cancelled);
            Files.createDirectories(command.directory());
            RunnerPolicy.rejectLinks(command.directory());
            Path input = Files.createDirectory(command.directory().resolve("input"));
            Path output = Files.createDirectory(command.directory().resolve("output"));
            if (Files.getFileStore(output).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rwx------"));
            Files.write(
                    input.resolve("request.json"),
                    mapper.writeValueAsBytes(command.request()),
                    StandardOpenOption.CREATE_NEW);
            JsonNode manifest = manifests.job(command, input, output);
            Path file = command.directory().resolve("job.json");
            Files.write(file, mapper.writeValueAsBytes(manifest), StandardOpenOption.CREATE_NEW);
            verifyNetwork(manifests.labels(command.executionId(), command.attemptId()), deadline, cancelled);
            checkCancelled(cancelled);
            submitted = true;
            JsonNode created = json(
                    List.of("create", "--filename=" + file.toAbsolutePath(), "--output=json", "--validate=strict"),
                    deadline,
                    cancelled);
            verifyOwned(created, execution, false);
            jobUid = uid(created);
            verifyJobPolicy(created, manifest);
            PodVerifier.verify(
                    created.path("spec").path("template").path("spec"),
                    manifest.path("spec").path("template").path("spec"));
            while (true) {
                checkCancelled(cancelled);
                checkOutput(output, settings.outputLimit(command));
                JsonNode job = json(
                        List.of("get", "jobs.batch", name, "--ignore-not-found=true", "--output=json"),
                        deadline,
                        cancelled);
                if (job.isMissingNode()) throw new IOException("KUBERNETES_JOB_DISAPPEARED");
                verifyOwned(job, execution, false);
                if (!jobUid.equals(uid(job))) throw new IOException("KUBERNETES_JOB_IDENTITY_CHANGED");
                verifyJobPolicy(job, manifest);
                List<JsonNode> pods = list(
                        "pods", manifests.selector(command.executionId(), command.attemptId()), deadline, cancelled);
                if (pods.size() > 1) throw new IOException("KUBERNETES_MULTIPLE_ATTEMPT_PODS");
                if (!pods.isEmpty()) {
                    JsonNode pod = pods.getFirst();
                    verifyOwned(pod, execution, true);
                    if (!ownedBy(pod, name, jobUid)) throw new IOException("KUBERNETES_POD_OWNER_MISMATCH");
                    PodVerifier.verify(
                            pod.path("spec"),
                            manifest.path("spec").path("template").path("spec"));
                    Map<String, String> actualLabels = new LinkedHashMap<>();
                    pod.path("metadata")
                            .path("labels")
                            .fields()
                            .forEachRemaining(e ->
                                    actualLabels.put(e.getKey(), e.getValue().asText()));
                    verifyNetwork(actualLabels, deadline, cancelled);
                    JsonNode statuses = pod.path("status").path("containerStatuses");
                    if (statuses.isArray()
                            && statuses.size() == 1
                            && "analyzer".equals(statuses.get(0).path("name").asText())) {
                        JsonNode status = statuses.get(0),
                                terminated = status.path("state").path("terminated");
                        if (terminated.isObject()) {
                            JsonNode exit = terminated.path("exitCode");
                            if (!exit.isIntegralNumber() || !exit.canConvertToInt())
                                throw new IOException("KUBERNETES_EXIT_STATUS_MISSING");
                            String imageId = status.path("imageID").asText();
                            if (imageId.length() > 700 || !imageId.matches("[A-Za-z0-9._:/@+-]*sha256:[0-9a-f]{64}"))
                                throw new IOException("KUBERNETES_IMAGE_ID_UNVERIFIED");
                            String digest = KubernetesSettings.digest(command.imageReference());
                            Files.write(
                                    command.directory().resolve("kubernetes-execution.json"),
                                    mapper.writeValueAsBytes(Map.of(
                                            "jobUid",
                                            jobUid,
                                            "podUid",
                                            uid(pod),
                                            "requestedImage",
                                            command.imageReference(),
                                            "imageDigest",
                                            digest,
                                            "runtimeImageId",
                                            imageId,
                                            "exitCode",
                                            exit.intValue(),
                                            "networkPolicy",
                                            settings.networkPolicy())),
                                    StandardOpenOption.CREATE_NEW);
                            byte[] logs = kubectl.execute(
                                    List.of(
                                            "logs",
                                            pod.path("metadata").path("name").asText(),
                                            "--container=analyzer",
                                            "--limit-bytes=4194304",
                                            "--tail=10000",
                                            "--timestamps=true"),
                                    remaining(deadline),
                                    4194304,
                                    cancelled);
                            Files.write(
                                    command.directory().resolve("analyzer.log"), logs, StandardOpenOption.CREATE_NEW);
                            checkOutput(output, settings.outputLimit(command));
                            // Exit 10 is a valid partial analyzer result even though Kubernetes calls the Job failed.
                            return validator.validate(command, output, digest, exit.intValue());
                        }
                        String reason = status.path("state")
                                .path("waiting")
                                .path("reason")
                                .asText();
                        if (List.of(
                                        "ErrImagePull",
                                        "ImagePullBackOff",
                                        "InvalidImageName",
                                        "CreateContainerConfigError")
                                .contains(reason)) throw new IOException("KUBERNETES_CONTAINER_START_FAILED");
                    }
                }
                for (JsonNode condition : job.path("status").path("conditions")) {
                    if ("True".equals(condition.path("status").asText())
                            && "Failed".equals(condition.path("type").asText()))
                        throw new IOException(
                                "DeadlineExceeded"
                                                .equals(condition.path("reason").asText())
                                        ? "ANALYZER_TIMEOUT"
                                        : "ANALYZER_EXECUTION_FAILED");
                    if ("True".equals(condition.path("status").asText())
                            && "Complete".equals(condition.path("type").asText()))
                        throw new IOException("KUBERNETES_EXIT_STATUS_MISSING");
                }
                pause(deadline);
            }
        } catch (IOException | RuntimeException | Error ex) {
            failure = ex;
            throw ex;
        } finally {
            try {
                if (submitted)
                    try {
                        cleanup(execution, jobUid);
                    } catch (IOException cleanup) {
                        if (failure != null) failure.addSuppressed(cleanup);
                        else throw cleanup;
                    }
            } finally {
                active.remove(execution, stop);
            }
        }
    }

    @Override
    public List<RunningExecution> running() throws IOException {
        return discover(manifests.selector());
    }

    @Override
    public void cancel(UUID executionId) throws IOException {
        active.forEach((key, flag) -> {
            if (key.executionId().equals(executionId)) flag.set(true);
        });
        IOException failure = null;
        for (RunningExecution execution : discover(manifests.selector(executionId, null))) {
            try {
                cancelAttempt(execution);
            } catch (IOException ex) {
                if (failure == null) failure = ex;
                else failure.addSuppressed(ex);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void cancelAttempt(RunningExecution execution) throws IOException {
        AtomicBoolean local = active.get(execution);
        if (local != null) local.set(true);
        cleanup(execution, null);
    }

    private List<RunningExecution> discover(String selector) throws IOException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        var result = new LinkedHashSet<RunningExecution>();
        for (String resource : List.of("jobs.batch", "pods")) {
            for (JsonNode item : list(resource, selector, deadline, () -> false)) {
                try {
                    var labels = item.path("metadata").path("labels");
                    var execution = new RunningExecution(
                            UUID.fromString(
                                    labels.path(KubernetesManifests.EXECUTION).asText()),
                            UUID.fromString(
                                    labels.path(KubernetesManifests.ATTEMPT).asText()));
                    verifyOwned(item, execution, resource.equals("pods"));
                    result.add(execution);
                } catch (IllegalArgumentException ex) {
                    throw new IOException("KUBERNETES_INVALID_EXECUTION_LABELS");
                }
            }
        }
        return List.copyOf(result);
    }

    private void cleanup(RunningExecution execution, String expectedUid) throws IOException {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + Duration.ofSeconds(40).toNanos();
        IOException last = null;
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    String name = KubernetesManifests.jobName(execution.executionId(), execution.attemptId());
                    JsonNode job = json(
                            List.of("get", "jobs.batch", name, "--ignore-not-found=true", "--output=json"),
                            deadline,
                            () -> false);
                    if (!job.isMissingNode()) {
                        verifyOwned(job, execution, false);
                        if (expectedUid != null && !expectedUid.equals(uid(job)))
                            throw new IOException("KUBERNETES_JOB_IDENTITY_CHANGED");
                        kubectl.execute(
                                List.of(
                                        "delete",
                                        "jobs.batch",
                                        name,
                                        "--ignore-not-found=true",
                                        "--cascade=foreground",
                                        "--wait=false"),
                                remaining(deadline),
                                65536,
                                () -> false);
                    }
                    while (true) {
                        List<JsonNode> pods = list(
                                "pods",
                                manifests.selector(execution.executionId(), execution.attemptId()),
                                deadline,
                                () -> false);
                        for (JsonNode pod : pods) {
                            verifyOwned(pod, execution, true);
                            if (expectedUid != null && !ownedBy(pod, name, expectedUid))
                                throw new IOException("KUBERNETES_POD_OWNER_MISMATCH");
                            if (!pod.path("metadata").hasNonNull("deletionTimestamp"))
                                kubectl.execute(
                                        List.of(
                                                "delete",
                                                "pods",
                                                pod.path("metadata")
                                                        .path("name")
                                                        .asText(),
                                                "--ignore-not-found=true",
                                                "--grace-period=5",
                                                "--wait=false"),
                                        remaining(deadline),
                                        65536,
                                        () -> false);
                        }
                        JsonNode remainingJob = json(
                                List.of("get", "jobs.batch", name, "--ignore-not-found=true", "--output=json"),
                                deadline,
                                () -> false);
                        if (pods.isEmpty() && remainingJob.isMissingNode()) return;
                        pause(deadline);
                    }
                } catch (IOException ex) {
                    last = ex;
                }
            }
            throw new IOException("KUBERNETES_CLEANUP_FAILED", last);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void verifyNetwork(Map<String, String> labels, long deadline, BooleanSupplier cancelled)
            throws IOException {
        network.verify(
                json(
                        List.of("get", "networkpolicies.networking.k8s.io", "--output=json", "--chunk-size=100"),
                        deadline,
                        cancelled),
                labels);
    }

    private List<JsonNode> list(String resource, String selector, long deadline, BooleanSupplier cancelled)
            throws IOException {
        JsonNode response = json(
                List.of("get", resource, "--selector=" + selector, "--output=json", "--chunk-size=100"),
                deadline,
                cancelled);
        JsonNode items = response.path("items");
        if (!items.isArray()
                || items.size() > 1000
                || !response.path("metadata").path("continue").asText().isEmpty())
            throw new IOException("KUBERNETES_LIST_LIMIT_OR_INVALID");
        var result = new ArrayList<JsonNode>();
        items.forEach(result::add);
        return result;
    }

    private JsonNode json(List<String> args, long deadline, BooleanSupplier cancelled) throws IOException {
        return kubectl.json(args, remaining(deadline), cancelled);
    }

    private Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("ANALYZER_TIMEOUT");
        return Duration.ofNanos(Math.min(
                nanos, Duration.ofSeconds(settings.commandTimeoutSeconds()).toNanos()));
    }

    private void pause(long deadline) throws IOException {
        try {
            Thread.sleep(Math.min(
                    settings.pollMillis(), Math.max(1, remaining(deadline).toMillis())));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("PROCESS_CANCELLED");
        }
    }

    private void verifyOwned(JsonNode item, RunningExecution execution, boolean pod) throws IOException {
        JsonNode metadata = item.path("metadata"), labels = metadata.path("labels");
        if (!settings.namespace().equals(metadata.path("namespace").asText()))
            throw new IOException("KUBERNETES_RESOURCE_SCOPE_MISMATCH");
        for (var label :
                manifests.labels(execution.executionId(), execution.attemptId()).entrySet())
            if (!label.getValue().equals(labels.path(label.getKey()).asText()))
                throw new IOException("KUBERNETES_RESOURCE_SCOPE_MISMATCH");
        String name = metadata.path("name").asText(),
                expected = KubernetesManifests.jobName(execution.executionId(), execution.attemptId());
        if (pod ? !name.matches(expected + "-[a-z0-9-]{1,30}") : !name.equals(expected))
            throw new IOException("KUBERNETES_RESOURCE_NAME_MISMATCH");
        uid(item);
        if (pod && !ownedBy(item, expected, null)) throw new IOException("KUBERNETES_POD_OWNER_MISMATCH");
    }

    private static boolean ownedBy(JsonNode pod, String name, String uid) {
        for (JsonNode owner : pod.path("metadata").path("ownerReferences"))
            if ("Job".equals(owner.path("kind").asText())
                    && name.equals(owner.path("name").asText())
                    && owner.path("controller").asBoolean()
                    && (uid == null || uid.equals(owner.path("uid").asText()))) return true;
        return false;
    }

    private static String uid(JsonNode object) throws IOException {
        String uid = object.path("metadata").path("uid").asText();
        try {
            UUID.fromString(uid);
            return uid;
        } catch (IllegalArgumentException ex) {
            throw new IOException("KUBERNETES_RESOURCE_UID_MISSING");
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("PROCESS_CANCELLED");
    }

    private static void verifyJobPolicy(JsonNode actual, JsonNode expected) throws IOException {
        for (String field : List.of(
                "backoffLimit", "completions", "parallelism", "activeDeadlineSeconds", "ttlSecondsAfterFinished"))
            if (!actual.path("spec").path(field).equals(expected.path("spec").path(field)))
                throw new IOException("KUBERNETES_JOB_POLICY_CHANGED");
        if (actual.path("spec").path("suspend").asBoolean()) throw new IOException("KUBERNETES_JOB_POLICY_CHANGED");
    }

    static void checkOutput(Path output, long limit) throws IOException {
        AnalyzerOutputBudget.check(output, limit);
    }
}
