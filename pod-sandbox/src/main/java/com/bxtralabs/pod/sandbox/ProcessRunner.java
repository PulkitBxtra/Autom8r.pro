package com.bxtralabs.pod.sandbox;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

// Runs one script in a fresh JVM (ScriptRunner), so nothing a script does outlives its run or
// reaches another one. Each child gets:
//   - no environment variables
//   - its own empty working directory, deleted afterwards
//   - 128 MB of heap, and a kill once its timeout (plus startup) has passed
// and, depending on where this runs (Isolation):
//   - UID           (Linux container, this service as root): the child runs as its own
//                   throwaway user id with no privileges, so it can't read this service's
//                   memory or environment, or another script running at the same time
//   - SANDBOX_EXEC  (macOS, local development): no network, no starting programs, no reading
//                   the user's files, no writing outside its directory
//   - NONE          only the above; for tests
// The container this runs in has no network access (deploy/docker-compose.yml).
public final class ProcessRunner {

    public enum Isolation { NONE, SANDBOX_EXEC, UID }

    // Time to start the JVM and compile, on top of the script's own timeout.
    static final long STARTUP_GRACE_MS = 5_000;
    private static final int STDOUT_LIMIT = 2 * 1024 * 1024;
    private static final int STDERR_LIMIT = 8 * 1024;
    // Child user ids in UID mode: BASE_UID + slot. Nothing in the image uses these.
    static final int BASE_UID = 20_000;

    // The runner couldn't be started or crashed without answering: not the script's fault.
    public static final class UnavailableException extends Exception {
        public UnavailableException(String message) {
            super(message);
        }
    }

    // Builds the child's command, given its directory and slot.
    public interface Command {
        List<String> build(Path dir, int slot) throws IOException;
    }

    private final Command command;
    private final Isolation isolation;
    private final long graceMs;

    public ProcessRunner(Command command, Isolation isolation, long graceMs) {
        this.command = command;
        this.isolation = isolation;
        this.graceMs = graceMs;
    }

    public static ProcessRunner forJar(Path jar, Isolation isolation) {
        return new ProcessRunner((dir, slot) -> javaCommand(jar, dir, slot, isolation), isolation, STARTUP_GRACE_MS);
    }

    // The runner's JSON answer (see ScriptRunner) for a request, or one made up here when the
    // child was killed or ran out of memory.
    public String run(String requestJson, int timeoutSeconds, int slot) throws UnavailableException {
        Path dir = null;
        Process process = null;
        try {
            dir = Files.createTempDirectory("autom8r-code-");
            Path work = Files.createDirectory(dir.resolve("work"));
            if (isolation == Isolation.UID) {
                // Only the child's own user can use its directory.
                int uid = BASE_UID + slot;
                Files.setAttribute(work, "unix:uid", uid);
                Files.setAttribute(work, "unix:gid", uid);
                Files.setPosixFilePermissions(work, PosixFilePermissions.fromString("rwx------"));
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx--x--x"));
            }
            ProcessBuilder builder = new ProcessBuilder(command.build(dir, slot)).directory(work.toFile());
            builder.environment().clear();
            process = builder.start();

            Process p = process;
            CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readCapped(p.getInputStream(), STDOUT_LIMIT));
            CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readCapped(p.getErrorStream(), STDERR_LIMIT));
            try (OutputStream in = process.getOutputStream()) {
                in.write(requestJson.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // It exited before reading everything; what it printed says why.
            }

            if (!process.waitFor(timeoutSeconds * 1000L + graceMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return failure("timeout", "The script ran longer than " + timeoutSeconds + " second" + (timeoutSeconds == 1 ? "" : "s"));
            }
            String out = new String(stdout.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
            String err = new String(stderr.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            if (!out.isEmpty()) {
                try {
                    if (new JsonSlurperClassic().parseText(out) instanceof Map) {
                        return out;
                    }
                } catch (RuntimeException notJson) {
                    // treated as a crash below
                }
            }
            if (err.contains("OutOfMemoryError") || err.contains("Java heap space")) {
                return failure("memory", "The script used more memory than a Code step may (128 MB)");
            }
            String firstLine = err.lines().filter(l -> !l.isBlank()).findFirst().orElse("no output");
            throw new UnavailableException("The code runner stopped without an answer (exit " + process.exitValue() + ": " + firstLine + ")");
        } catch (IOException e) {
            throw new UnavailableException("The code runner couldn't be started: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnavailableException("Interrupted while the script ran");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new UnavailableException("The code runner's output couldn't be read");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteQuietly(dir);
        }
    }

    static String failure(String kind, String error) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", kind);
        out.put("error", error);
        out.put("logs", "");
        return JsonOutput.toJson(out);
    }

    static List<String> javaCommand(Path jar, Path dir, int slot, Isolation isolation) throws IOException {
        Path java = realPath(Path.of(System.getProperty("java.home"), "bin", "java"));
        Path work = realPath(dir.resolve("work"));
        List<String> cmd = new ArrayList<>();
        switch (isolation) {
            case SANDBOX_EXEC -> {
                Path profile = dir.resolve("profile.sb");
                Files.writeString(profile, macProfile(java, realPath(jar), work));
                cmd.addAll(List.of("/usr/bin/sandbox-exec", "-f", profile.toString()));
            }
            case UID -> {
                int uid = BASE_UID + slot;
                cmd.addAll(List.of("setpriv", "--reuid=" + uid, "--regid=" + uid, "--clear-groups", "--no-new-privs", "--"));
            }
            case NONE -> {
            }
        }
        cmd.addAll(List.of(java.toString(),
                "-Xmx128m", "-Xss2m", "-XX:MaxMetaspaceSize=96m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-XX:-UsePerfData", "-XX:+DisableAttachMechanism", "-Djava.io.tmpdir=" + work,
                "-cp", jar.toString(), ScriptRunner.class.getName()));
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

    // auto: sandbox-exec on macOS; on Linux, per-run user ids when this runs as root with
    // setpriv available; otherwise none (and the service says so when it starts).
    public static Isolation choose(String setting) {
        String s = setting == null ? "auto" : setting.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "none", "off" -> Isolation.NONE;
            case "sandbox-exec" -> Isolation.SANDBOX_EXEC;
            case "uid" -> Isolation.UID;
            default -> {
                String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                if (os.contains("mac") && Files.isExecutable(Path.of("/usr/bin/sandbox-exec"))) yield Isolation.SANDBOX_EXEC;
                if (os.contains("linux") && "root".equals(System.getProperty("user.name")) && onPath("setpriv")) yield Isolation.UID;
                yield Isolation.NONE;
            }
        };
    }

    private static boolean onPath(String program) {
        for (String dir : System.getenv().getOrDefault("PATH", "/usr/bin:/bin").split(":")) {
            if (Files.isExecutable(Path.of(dir, program))) return true;
        }
        return false;
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
