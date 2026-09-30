package com.bxtralabs.pod.processor.service.handlers.code;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

// Runs a Code step's script in its own JVM (pod-sandbox's jar), never in this process: a script
// must not reach the processor's environment (DB password, INTERNAL_API_TOKEN), its memory, or
// other users' runs. The child gets:
//   - no environment variables at all
//   - a fresh empty working directory, deleted afterwards
//   - 128 MB of heap, and a kill once the script's timeout (plus startup) has passed
//   - on macOS, sandbox-exec: no network, no process spawning, no reading the user's files, no
//     writing outside its directory
// In production the runner must also live in a container with no network egress (see the F
// roadmap step); on Linux nothing here blocks the network.
@Component
public class CodeSandbox {

    // Time to start the JVM and compile, on top of the script's own timeout.
    static final long STARTUP_GRACE_MS = 5_000;
    private static final int STDOUT_LIMIT = 2 * 1024 * 1024;
    private static final int STDERR_LIMIT = 8 * 1024;

    // What the runner answered (see pod-sandbox's ScriptRunner).
    //   kind: script, blocked, timeout, memory, result (on failure)
    public record Result(boolean ok, Object result, String logs, String error, String kind, Integer line) {
    }

    // The runner couldn't be started or crashed without answering: not the script's fault, so the
    // step is retried.
    public static class SandboxUnavailableException extends Exception {
        public SandboxUnavailableException(String message) {
            super(message);
        }
    }

    private final JsonMapper jsonMapper;
    // Builds the command for a run, given its private directory.
    private final Function<Path, List<String>> command;
    private final long graceMs;

    @Autowired
    public CodeSandbox(JsonMapper jsonMapper,
                       @Value("${code.sandbox.jar:../pod-sandbox/target/pod-sandbox.jar}") String jar,
                       @Value("${code.sandbox.os-sandbox:auto}") String osSandbox) {
        this(jsonMapper, dir -> javaCommand(Path.of(jar).toAbsolutePath().normalize(), dir, useSandboxExec(osSandbox)), STARTUP_GRACE_MS);
    }

    CodeSandbox(JsonMapper jsonMapper, Function<Path, List<String>> command, long graceMs) {
        this.jsonMapper = jsonMapper;
        this.command = command;
        this.graceMs = graceMs;
    }

    public Result run(String script, Map<String, Object> bindings, int timeoutSeconds) throws SandboxUnavailableException {
        Path dir = null;
        Process process = null;
        try {
            dir = Files.createTempDirectory("autom8r-code-");
            Path work = Files.createDirectory(dir.resolve("work"));
            ProcessBuilder builder = new ProcessBuilder(command.apply(dir)).directory(work.toFile());
            builder.environment().clear();
            process = builder.start();

            Process p = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readCapped(p.getInputStream(), STDOUT_LIMIT));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readCapped(p.getErrorStream(), STDERR_LIMIT));

            Map<String, Object> request = new LinkedHashMap<>();
            request.put("script", script);
            request.put("bindings", bindings);
            request.put("timeoutSeconds", timeoutSeconds);
            try (OutputStream in = process.getOutputStream()) {
                in.write(jsonMapper.writeValueAsBytes(request));
            } catch (IOException ignored) {
                // It exited before reading everything; what it printed says why.
            }

            if (!process.waitFor(timeoutSeconds * 1000L + graceMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(false, null, "", "The script ran longer than " + timeoutSeconds + " second"
                        + (timeoutSeconds == 1 ? "" : "s"), "timeout", null);
            }
            String out = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String err = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            if (!out.isEmpty()) {
                try {
                    Map<?, ?> answer = jsonMapper.readValue(out, Map.class);
                    return new Result(Boolean.TRUE.equals(answer.get("ok")), answer.get("result"),
                            answer.get("logs") == null ? "" : String.valueOf(answer.get("logs")),
                            answer.get("error") == null ? null : String.valueOf(answer.get("error")),
                            answer.get("kind") == null ? null : String.valueOf(answer.get("kind")),
                            answer.get("line") instanceof Number n ? n.intValue() : null);
                } catch (RuntimeException notJson) {
                    // falls through: treated as a crash below
                }
            }
            if (err.contains("OutOfMemoryError") || err.contains("Java heap space")) {
                return new Result(false, null, "", "The script used more memory than a Code step may (128 MB)", "memory", null);
            }
            String firstLine = err.lines().filter(l -> !l.isBlank()).findFirst().orElse("no output");
            throw new SandboxUnavailableException("The code runner stopped without an answer (exit "
                    + process.exitValue() + ": " + firstLine + ")");
        } catch (IOException e) {
            throw new SandboxUnavailableException("The code runner couldn't be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SandboxUnavailableException("Interrupted while the script ran");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new SandboxUnavailableException("The code runner's output couldn't be read");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(dir);
        }
    }

    static List<String> javaCommand(Path jar, Path dir, boolean sandboxExec) {
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("The code runner isn't installed (no " + jar
                    + "; build pod-sandbox or set SANDBOX_JAR)");
        }
        Path java = realPath(Path.of(System.getProperty("java.home"), "bin", "java"));
        Path work = dir.resolve("work");
        List<String> cmd = new ArrayList<>();
        if (sandboxExec) {
            Path profile = dir.resolve("profile.sb");
            try {
                Files.writeString(profile, macProfile(java, realPath(jar), realPath(work)));
            } catch (IOException e) {
                throw new IllegalStateException("Couldn't write the sandbox profile: " + e.getMessage());
            }
            cmd.addAll(List.of("/usr/bin/sandbox-exec", "-f", profile.toString()));
        }
        cmd.addAll(List.of(java.toString(),
                "-Xmx128m", "-Xss2m", "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-XX:-UsePerfData", "-XX:+DisableAttachMechanism", "-Djava.io.tmpdir=" + work,
                "-jar", jar.toString()));
        return cmd;
    }

    // Everything is allowed except what a script could use to reach out: the network, starting
    // programs (other than this java), writing outside its directory, and reading the places
    // user files and secrets live. Later rules win.
    static String macProfile(Path java, Path jar, Path work) {
        return """
                (version 1)
                (allow default)
                (deny network*)
                (deny process-fork)
                (deny process-exec)
                (allow process-exec (literal "%1$s"))
                (deny file-write*)
                (allow file-write* (subpath "%3$s") (literal "/dev/null"))
                (deny file-read* (subpath "/Users") (subpath "/private/tmp") (subpath "/private/var/folders") (subpath "/Volumes") (subpath "/private/etc"))
                (allow file-read-metadata)
                (allow file-read* (literal "%2$s") (subpath "%3$s"))
                """.formatted(java, jar, work);
    }

    static boolean useSandboxExec(String setting) {
        return switch (setting == null ? "auto" : setting.trim().toLowerCase()) {
            case "off", "none", "false" -> false;
            case "sandbox-exec", "on", "true" -> true;
            default -> System.getProperty("os.name", "").toLowerCase().contains("mac")
                    && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"));
        };
    }

    private static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize();
        }
    }

    private static byte[] readCapped(InputStream in, int limit) {
        try (in) {
            byte[] kept = in.readNBytes(limit);
            in.transferTo(OutputStream.nullOutputStream()); // drain the rest so the child never blocks
            return kept;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // a leftover temp directory is harmless
        }
    }
}
