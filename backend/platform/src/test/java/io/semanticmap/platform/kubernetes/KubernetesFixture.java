package io.semanticmap.platform.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.semanticmap.contract.Protocol;
import io.semanticmap.platform.runner.AnalyzerExecutionPort;
import io.semanticmap.platform.runner.AnalyzerOutputValidator;
import io.semanticmap.platform.runner.BoundedProcess;
import io.semanticmap.platform.runner.RunnerPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.springframework.mock.env.MockEnvironment;

final class KubernetesFixture {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String DIGEST = "sha256:" + "a".repeat(64);
    static final String IMAGE = "registry.example.com/analyzer@" + DIGEST;

    static MockEnvironment environment(Path root) {
        return new MockEnvironment()
                .withProperty("semantic.artifact-root", root.toString())
                .withProperty("semantic.runner.mode", "kubernetes")
                .withProperty("semantic.runner.kubernetes.namespace", "semanticmap")
                .withProperty("semantic.runner.kubernetes.pvc", "artifacts")
                .withProperty("semantic.runner.kubernetes.network-policy", "analyzer-deny-all")
                .withProperty("semantic.runner.kubernetes.allowed-images", IMAGE)
                .withProperty("semantic.runner.kubernetes.poll-millis", "100");
    }

    static KubernetesSettings settings(MockEnvironment environment) {
        return new KubernetesSettings(environment, new RunnerPolicy(environment));
    }

    static AnalyzerExecutionPort.Command command(Path root, BooleanSupplier cancelled, int timeout) throws IOException {
        Path source = Files.createDirectories(root.resolve("run/source"));
        var request = new Protocol.Request(
                "1.0",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                new Protocol.Revision("abc", "main"),
                new Protocol.Component(".", List.of("JAVA"), List.of("SPRING_BOOT"), "MAVEN"),
                List.of("SYMBOLS"),
                List.of(),
                new Protocol.Policy("DENY", false, false, true, false, timeout, 134217728));
        return new AnalyzerExecutionPort.Command(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "java-spring",
                "0.1.0",
                IMAGE,
                source,
                root.resolve("run/executions").resolve(UUID.randomUUID().toString()),
                request,
                cancelled);
    }

    static ObjectNode policyList() {
        return JSON.valueToTree(Map.of(
                "items",
                List.of(Map.of(
                        "metadata",
                        Map.of("name", "analyzer-deny-all", "namespace", "semanticmap"),
                        "spec",
                        Map.of(
                                "podSelector",
                                Map.of(),
                                "policyTypes",
                                List.of("Ingress", "Egress"),
                                "ingress",
                                List.of(),
                                "egress",
                                List.of())))));
    }

    static void output(Path directory, boolean partial) throws IOException {
        Files.writeString(
                directory.resolve("manifest.json"),
                """
                {"contractVersion":"1.0","analyzer":{"id":"java-spring","version":"0.1.0","imageDigest":"%s"},"status":"%s",
                 "capabilitiesCompleted":["SYMBOLS"],"capabilitiesPartial":[],"capabilitiesFailed":[],"factCount":0,"diagnosticCount":0,
                 "startedAt":"2026-09-02T00:00:00Z","finishedAt":"2026-09-02T00:00:01Z"}
                """
                        .formatted(DIGEST, partial ? "PARTIAL" : "SUCCEEDED"));
        Files.writeString(
                directory.resolve("coverage.json"),
                """
                {"contractVersion":"1.0","filesDiscovered":1,"filesParsed":1,"filesFailed":0,
                 "capabilities":[{"name":"SYMBOLS","status":"COMPLETED","limitations":[]}],"metadata":{}}
                """);
        Files.writeString(
                directory.resolve("statistics.json"),
                "{\"contractVersion\":\"1.0\",\"factCount\":0,\"diagnosticCount\":0,\"factsByKind\":{},\"metadata\":{}}");
        Files.writeString(directory.resolve("facts.ndjson"), "");
        Files.writeString(directory.resolve("diagnostics.ndjson"), "");
    }

    static final class Api implements KubectlProcess.Executor {
        final List<List<String>> calls = new ArrayList<>();
        ObjectNode job, pod;
        JsonNode policies = policyList();
        int exitCode;
        boolean pending, duplicatePod, ambiguousCreate, invalidOutput, oversizeLogs, failDelete;
        Runnable beforePodRead = () -> {};
        Consumer<ObjectNode> admission = ignored -> {};
        Consumer<ObjectNode> jobAdmission = ignored -> {};

        KubernetesAnalyzerExecutionAdapter adapter(KubernetesSettings settings) {
            return new KubernetesAnalyzerExecutionAdapter(
                    settings, new KubectlProcess(settings, this), JSON, new AnalyzerOutputValidator(JSON));
        }

        @Override
        public BoundedProcess.Result run(List<String> args, Duration timeout, long limit, BooleanSupplier cancelled)
                throws IOException {
            calls.add(args);
            int commandIndex = 1;
            while (args.get(commandIndex).startsWith("--")) commandIndex++;
            String command = args.get(commandIndex), resource = args.get(commandIndex + 1);
            if (command.equals("create")) {
                Path manifest = Path.of(resource.substring("--filename=".length()));
                job = (ObjectNode) JSON.readTree(manifest.toFile());
                ((ObjectNode) job.path("metadata")).put("uid", UUID.randomUUID().toString());
                jobAdmission.accept(job);
                pod = JSON.createObjectNode();
                pod.put("apiVersion", "v1");
                pod.put("kind", "Pod");
                ObjectNode metadata = job.path("metadata").deepCopy();
                metadata.put("name", metadata.path("name").asText() + "-abcde");
                metadata.put("uid", UUID.randomUUID().toString());
                metadata.set(
                        "ownerReferences",
                        JSON.valueToTree(List.of(Map.of(
                                "kind",
                                "Job",
                                "name",
                                job.path("metadata").path("name").asText(),
                                "uid",
                                job.path("metadata").path("uid").asText(),
                                "controller",
                                true))));
                pod.set("metadata", metadata);
                pod.set("spec", job.path("spec").path("template").path("spec").deepCopy());
                if (!pending)
                    pod.set(
                            "status",
                            JSON.valueToTree(Map.of(
                                    "containerStatuses",
                                    List.of(Map.of(
                                            "name",
                                            "analyzer",
                                            "imageID",
                                            "containerd://sha256:" + "b".repeat(64),
                                            "state",
                                            Map.of("terminated", Map.of("exitCode", exitCode)))))));
                admission.accept(pod);
                if (!invalidOutput) output(manifest.getParent().resolve("output"), false);
                if (ambiguousCreate) throw new IOException("PROCESS_TIMEOUT");
                return result(job);
            }
            if (command.equals("get")) {
                if (resource.equals("networkpolicies.networking.k8s.io")) return result(policies);
                boolean listing = args.stream().anyMatch(a -> a.startsWith("--selector="));
                if (resource.equals("jobs.batch")) return listing ? items(job) : result(job);
                if (resource.equals("pods")) {
                    beforePodRead.run();
                    if (duplicatePod && pod != null) return result(Map.of("items", List.of(pod, pod)));
                    return items(pod);
                }
            }
            if (command.equals("logs"))
                return new BoundedProcess.Result(
                        0,
                        oversizeLogs
                                ? new byte[4194305]
                                : "actual log\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (command.equals("delete")) {
                if (failDelete) throw new IOException("PROCESS_TIMEOUT");
                if (resource.equals("jobs.batch")) job = null;
                if (resource.equals("pods")) pod = null;
                return result(null);
            }
            throw new AssertionError("Unexpected kubectl arguments: " + args);
        }

        private BoundedProcess.Result items(JsonNode node) throws IOException {
            return result(Map.of("items", node == null ? List.of() : List.of(node)));
        }

        private BoundedProcess.Result result(Object value) throws IOException {
            return new BoundedProcess.Result(0, value == null ? new byte[0] : JSON.writeValueAsBytes(value));
        }

        boolean called(String command, String resource) {
            return calls.stream().anyMatch(args -> args.contains(command) && args.contains(resource));
        }
    }
}
