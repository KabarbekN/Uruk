package io.semanticmap.platform.runner;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Runs fixed argument lists without a shell, draining both pipes with bounded memory. */
public final class BoundedProcess {
    private BoundedProcess() {}

    public record Result(int exitCode, byte[] stdout) {
        public String text() {
            return new String(stdout, StandardCharsets.UTF_8).trim();
        }
    }

    public record CapturedResult(int exitCode, byte[] stdout, byte[] stderr) {}

    public static Result run(
            List<String> command,
            Path directory,
            Map<String, String> environment,
            Duration timeout,
            long outputLimit,
            BooleanSupplier cancelled)
            throws IOException {
        var result = capture(command, directory, environment, timeout, outputLimit, cancelled);
        return new Result(result.exitCode(), result.stdout());
    }

    /** Captured stderr belongs in restricted artifacts, never API errors: it can contain source or credentials. */
    public static CapturedResult capture(
            List<String> command,
            Path directory,
            Map<String, String> environment,
            Duration timeout,
            long outputLimit,
            BooleanSupplier cancelled)
            throws IOException {
        var builder = new ProcessBuilder(command);
        if (directory != null) builder.directory(directory.toFile());
        builder.environment().putAll(environment);
        Process process = builder.start();
        var stdout = CompletableFuture.supplyAsync(() -> read(process.getInputStream(), outputLimit, process));
        var stderr = CompletableFuture.supplyAsync(() -> read(process.getErrorStream(), 262144, process));
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process.getOutputStream().close();
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) throw new IOException("PROCESS_CANCELLED");
                if (System.nanoTime() > deadline) throw new IOException("PROCESS_TIMEOUT");
            }
            byte[] bytes = stdout.join();
            byte[] errors = stderr.join();
            if (cancelled.getAsBoolean()) throw new IOException("PROCESS_CANCELLED");
            return new CapturedResult(process.exitValue(), bytes, errors);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PROCESS_INTERRUPTED", e);
        } catch (java.util.concurrent.CompletionException e) {
            throw new IOException("PROCESS_OUTPUT_LIMIT_OR_IO", e.getCause());
        } finally {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static byte[] read(InputStream input, long limit, Process process) {
        try (input;
                var bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if ((long) bytes.size() + count > limit) {
                    process.destroyForcibly();
                    throw new IOException("PROCESS_OUTPUT_LIMIT");
                }
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
