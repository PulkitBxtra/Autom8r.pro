package com.bxtralabs.pod.sandbox;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScriptRunnerTest {

    private static Map<String, Object> run(String script) {
        return ScriptRunner.run(script, Map.of(), 5);
    }

    @Test
    void inputsAreVariablesAndTheReturnedMapIsTheResult() {
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("amount", new java.math.BigDecimal("12.5"));
        bindings.put("customer", Map.of("name", "ada"));
        bindings.put("items", List.of(Map.of("ok", true, "n", 1), Map.of("ok", false, "n", 2)));

        Map<String, Object> out = ScriptRunner.run("""
                println "total for ${customer.name}"
                return [total: amount * 2, who: "${customer.name.capitalize()}", ok: items.findAll { it.ok }*.n]
                """, bindings, 5);

        assertEquals(true, out.get("ok"), String.valueOf(out));
        assertEquals(Map.of("total", new java.math.BigDecimal("25.0"), "who", "Ada", "ok", List.of(1)), out.get("result"));
        assertEquals("total for ada\n", out.get("logs"));
    }

    @Test
    void datesAndOtherGroovyValuesBecomeJson() {
        Map<String, Object> out = run("""
                [when: java.time.LocalDate.of(2026, 9, 30), at: new Date(0), range: 1..3, chars: 'ab' as char[], nothing: null]
                """);
        assertEquals(true, out.get("ok"), String.valueOf(out));
        Map<?, ?> result = (Map<?, ?>) out.get("result");
        assertEquals("2026-09-30", result.get("when"));
        assertEquals("1970-01-01T00:00:00Z", result.get("at"));
        assertEquals(List.of(1, 2, 3), result.get("range"));
        assertEquals(List.of("a", "b"), result.get("chars"));
        assertTrue(result.containsKey("nothing"));
    }

    @Test
    void aValueThatIsNotDataSaysWhereItIs() {
        Map<String, Object> out = run("[total: 1, items: [[fn: { -> 1 }]]]");
        assertEquals("result", out.get("kind"));
        assertEquals("Items.0.fn is a closure, which can't be saved; use text, numbers, true/false, lists or maps", out.get("error"));
    }

    @Test
    void errorsSayWhatAndOnWhichLine() {
        Map<String, Object> missing = run("def a = 1\n\nreturn [x: amout]");
        assertEquals("script", missing.get("kind"));
        assertEquals("No such property: amout", missing.get("error"));
        assertEquals(3, missing.get("line"));

        Map<String, Object> thrown = run("println 'before'\nthrow new IllegalStateException('bad input')");
        assertEquals("bad input", thrown.get("error"));
        assertEquals(2, thrown.get("line"));
        assertEquals("before\n", thrown.get("logs"), "what it printed before failing is kept");

        Map<String, Object> syntax = run("def x = [1, 2\nreturn x");
        assertEquals("script", syntax.get("kind"));
        assertNotNull(syntax.get("line"));
    }

    @Test
    void aLoopThatNeverEndsIsStopped() {
        long started = System.nanoTime();
        Map<String, Object> out = ScriptRunner.run("int i = 0\nwhile (true) { i++ }", Map.of(), 1);
        assertEquals("timeout", out.get("kind"));
        assertEquals("The script ran longer than 1 second", out.get("error"));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 4);
    }

    @Test
    void printedTextIsCapped() {
        Map<String, Object> out = run("20000.times { print 'x' }\n[:]");
        String logs = (String) out.get("logs");
        assertTrue(logs.startsWith("x".repeat(ScriptRunner.LOG_LIMIT)));
        assertTrue(logs.endsWith("(only the first 10000 characters are kept)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "System.exit(0)",
            "System.getenv()",
            "def s = System\ns.getenv()",
            "Runtime.getRuntime().exec('ls')",
            "new File('/etc/passwd').text",
            "java.nio.file.Files.readString(java.nio.file.Path.of('/etc/passwd'))",
            "import java.nio.file.*\nFiles.list(Path.of('/'))",
            "'ls'.execute().text",
            "new URL('http://localhost:8084/').text",
            "'http://localhost:8084/'.toURL().text",
            "new Socket('localhost', 8084)",
            "[:].getClass()",
            "this.class.classLoader",
            "''.metaClass",
            "String.metaClass.foo = { -> 1 }",
            "Class.forName('java.lang.System')",
            "def m = 'getClass'\n''.\"$m\"()",
            "def p = 'class'\n''.\"$p\"",
            "''['class']",
            "new Thread({ -> }).start()",
            "java.util.concurrent.Executors.newFixedThreadPool(1)",
            "evaluate('System.exit(0)')",
            "new GroovyShell().evaluate('1')",
            "Eval.me('1')",
            "org.codehaus.groovy.runtime.InvokerHelper.invokeMethod(null, 'x', null)",
            "class Foo {}",
            "new Object() { String toString() { 'x' } }",
            "@groovy.transform.ASTTest(value = { System.exit(1) })\ndef x = 1",
            "@Grab('commons-io:commons-io:2.11.0')\nimport org.apache.commons.io.FileUtils",
            "import static java.lang.System.exit\nexit(0)",
            "def x = ''\nx.@value",
            "def f = ''.&getClass",
            "Class c = String",
    })
    void reachingOutsideTheScriptIsRefused(String script) {
        Map<String, Object> out = run(script);
        assertEquals(false, out.get("ok"), script + " -> " + out);
        assertEquals("blocked", out.get("kind"), script + " -> " + out);
        assertTrue(String.valueOf(out.get("error")).contains("available in Code steps")
                || String.valueOf(out.get("error")).contains("can't define classes"), out.get("error") + "");
    }

    @Test
    void everydayDataWorkIsAllowed() {
        Map<String, Object> out = run("""
                import java.time.format.DateTimeFormatter
                import groovy.json.JsonSlurper
                def data = new JsonSlurper().parseText('{"a": [3, 1, 2]}')
                def sorted = data.a.sort()
                def day = java.time.LocalDate.parse('2026-09-30').plusDays(1).format(DateTimeFormatter.ISO_DATE)
                def words = 'a b  c'.split(/\\s+/).collect { it.toUpperCase() }.join('-')
                def m = (1..5).collectEntries { [(it): it * it] }
                def total(list) { list.sum() }
                [sorted: sorted, day: day, words: words, max: Math.max(1, 2), squares: m[3], total: total(sorted),
                 matched: ('order-42' =~ /\\d+/)[0], round: new BigDecimal('2.345').setScale(2, java.math.RoundingMode.HALF_UP)]
                """);
        assertEquals(true, out.get("ok"), String.valueOf(out));
        Map<?, ?> r = (Map<?, ?>) out.get("result");
        assertEquals(List.of(1, 2, 3), r.get("sorted"));
        assertEquals("2026-10-01", r.get("day"));
        assertEquals("A-B-C", r.get("words"));
        assertEquals(9, r.get("squares"));
        assertEquals(6, r.get("total"));
        assertEquals("42", r.get("matched"));
        assertEquals(new java.math.BigDecimal("2.35"), r.get("round"));
    }

    // The way pod-processor uses it: a fresh JVM, JSON in on stdin, JSON out on stdout.
    @Test
    void asAProcessItAnswersOnStdoutAndStopsAScriptStuckOutsideALoop() throws Exception {
        Map<?, ?> ok = runProcess(Map.of("script", "println 'hi'\n[sum: a + b]", "bindings", Map.of("a", 1, "b", 2), "timeoutSeconds", 5));
        assertEquals(true, ok.get("ok"), String.valueOf(ok));
        assertEquals(Map.of("sum", 3), ok.get("result"));
        assertEquals("hi\n", ok.get("logs"));

        long started = System.nanoTime();
        Map<?, ?> stuck = runProcess(Map.of("script", "sleep(60000)", "timeoutSeconds", 1));
        assertEquals("timeout", stuck.get("kind"), String.valueOf(stuck));
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10);
    }

    private static Map<?, ?> runProcess(Map<String, Object> request) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ScriptRunner.class.getName())
                .redirectErrorStream(false).start();
        p.getOutputStream().write(JsonOutput.toJson(request).getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(15, TimeUnit.SECONDS));
        return (Map<?, ?>) new JsonSlurperClassic().parseText(out);
    }
}
