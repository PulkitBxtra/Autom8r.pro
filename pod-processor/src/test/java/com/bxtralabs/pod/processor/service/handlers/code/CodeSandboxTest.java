package com.bxtralabs.pod.processor.service.handlers.code;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CodeSandboxTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // A stand-in for the runner: a shell command reading the request on stdin.
    private static CodeSandbox shell(String script) {
        return new CodeSandbox(JSON, dir -> List.of("/bin/sh", "-c", script), 500);
    }

    @Test
    void theRunnerGetsNoEnvironmentAndItsAnswerIsRead() throws Exception {
        CodeSandbox sandbox = shell("cat > /dev/null; printf '{\"ok\":true,\"result\":{\"env\":\"%s\",\"dir\":\"%s\"},\"logs\":\"hi\"}' "
                + "\"$(env | grep -v -e '^PWD=' -e '^SHLVL=' -e '^_=' | tr '\\n' ' ')\" \"$(pwd)\"");
        CodeSandbox.Result r = sandbox.run("x", Map.of(), 5);
        assertTrue(r.ok(), String.valueOf(r));
        Map<?, ?> result = (Map<?, ?>) r.result();
        assertEquals("", String.valueOf(result.get("env")).trim(), "nothing from the processor's environment");
        assertTrue(String.valueOf(result.get("dir")).contains("autom8r-code-"), "runs in its own directory");
        assertFalse(Files.exists(Path.of(String.valueOf(result.get("dir")))), "which is deleted afterwards");
        assertEquals("hi", r.logs());
    }

    @Test
    void aRunnerThatOverrunsIsKilled() throws Exception {
        long started = System.nanoTime();
        CodeSandbox.Result r = shell("sleep 30").run("x", Map.of(), 1);
        assertEquals("timeout", r.kind());
        assertEquals("The script ran longer than 1 second", r.error());
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 5);
    }

    @Test
    void runningOutOfMemoryIsTheScriptsFaultAndOtherCrashesAreRetried() {
        assertEquals("memory", assertDoesNotThrow(() ->
                shell("echo 'java.lang.OutOfMemoryError: Java heap space' >&2; exit 3").run("x", Map.of(), 5)).kind());
        CodeSandbox.SandboxUnavailableException e = assertThrows(CodeSandbox.SandboxUnavailableException.class,
                () -> shell("echo 'Error: could not start' >&2; exit 1").run("x", Map.of(), 5));
        assertTrue(e.getMessage().contains("exit 1: Error: could not start"), e.getMessage());
    }

    @Test
    void aMissingRunnerSaysHowToFixIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new CodeSandbox(JSON, "/nowhere/pod-sandbox.jar", "off").run("x", Map.of(), 5));
        assertTrue(e.getMessage().contains("build pod-sandbox or set SANDBOX_JAR"), e.getMessage());
    }

    // With the real runner, when it has been built (../pod-sandbox: ./mvnw package).
    @Test
    void theRealRunnerRunsAScriptInsideTheOsSandbox() throws Exception {
        Path jar = Path.of("../pod-sandbox/target/pod-sandbox.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jar), "pod-sandbox isn't built");
        CodeSandbox sandbox = new CodeSandbox(JSON, jar.toString(), "auto");

        CodeSandbox.Result r = sandbox.run("println \"hi ${name}\"\n[total: amount * 2]", Map.of("amount", 21, "name", "ada"), 5);
        assertTrue(r.ok(), String.valueOf(r));
        assertEquals(Map.of("total", 42), r.result());
        assertEquals("hi ada\n", r.logs());

        CodeSandbox.Result blocked = sandbox.run("System.getenv()", Map.of(), 5);
        assertEquals("blocked", blocked.kind());
    }
}
