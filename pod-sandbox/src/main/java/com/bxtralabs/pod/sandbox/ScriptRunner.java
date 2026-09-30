package com.bxtralabs.pod.sandbox;

import groovy.json.JsonOutput;
import groovy.json.JsonSlurperClassic;
import groovy.lang.Binding;
import groovy.lang.GroovyCodeSource;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import groovy.transform.TimedInterrupt;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;
import java.util.concurrent.TimeoutException;

// Runs one Code step's Groovy script. SandboxServer starts this as a fresh JVM for every run
// (ProcessRunner: no environment, capped memory, its own user id, killed after the timeout), in
// a container with no network, so a script can never reach secrets, other pods or other users'
// runs. That is the security boundary; ScriptGuard (rejecting System, reflection, files,
// network... at compile time) is an extra layer.
//
// stdin:  {"script": "...", "bindings": {"name": value}, "timeoutSeconds": 10}
// stdout: {"ok": true, "result": <what the script returned, as JSON>, "logs": "<println output>"}
//      or {"ok": false, "kind": "script"|"blocked"|"timeout"|"memory"|"result", "error": "...",
//          "line": 3, "logs": "..."}
public final class ScriptRunner {

    public static final String SCRIPT_NAME = "Script";
    static final int LOG_LIMIT = 10_000;
    static final int RESULT_LIMIT = 1_000_000;
    private static final int MAX_DEPTH = 32;

    private ScriptRunner() {
    }

    public static void main(String[] args) throws Exception {
        // Our stdout carries only the result; anything else writing there would corrupt it.
        PrintStream result = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));

        Map<?, ?> request = (Map<?, ?>) new JsonSlurperClassic().parseText(
                new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        String script = String.valueOf(request.get("script"));
        Map<String, Object> bindings = new LinkedHashMap<>();
        if (request.get("bindings") instanceof Map<?, ?> b) {
            b.forEach((k, v) -> bindings.put(String.valueOf(k), v));
        }
        int timeoutSeconds = request.get("timeoutSeconds") instanceof Number n ? n.intValue() : 10;

        Object lock = new Object();
        boolean[] done = {false};
        // TimedInterrupt only checks at loops and calls; this catches a script stuck elsewhere
        // (one huge string operation). pod-processor also kills the process a little later.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutSeconds * 1000L + 1500);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                if (!done[0]) {
                    done[0] = true;
                    result.print(JsonOutput.toJson(timedOut(timeoutSeconds, "")));
                    result.flush();
                    Runtime.getRuntime().halt(0);
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        Map<String, Object> outcome = run(script, bindings, timeoutSeconds);
        synchronized (lock) {
            if (!done[0]) {
                done[0] = true;
                result.print(JsonOutput.toJson(outcome));
                result.flush();
            }
        }
        // Don't wait for threads a script may have left behind.
        Runtime.getRuntime().halt(0);
    }

    public static Map<String, Object> run(String script, Map<String, Object> bindings, int timeoutSeconds) {
        LimitedWriter logs = new LimitedWriter(LOG_LIMIT);
        try {
            Binding binding = new Binding();
            bindings.forEach(binding::setVariable);
            // println in a script writes to the "out" variable.
            binding.setVariable("out", new PrintWriter(logs, true));

            GroovyShell shell = new GroovyShell(ScriptRunner.class.getClassLoader(), binding, configuration(timeoutSeconds));
            Script parsed = shell.parse(new GroovyCodeSource(script, SCRIPT_NAME + ".groovy", "/groovy/script"));
            Object returned = parsed.run();

            Object json;
            try {
                json = jsonSafe(returned, "the result", 0);
            } catch (IllegalArgumentException e) {
                return failed("result", e.getMessage(), null, logs);
            }
            String text = JsonOutput.toJson(json);
            if (text.length() > RESULT_LIMIT) {
                return failed("result", "The script returned more than 1 MB of data", null, logs);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("result", json);
            out.put("logs", logs.text());
            return out;
        } catch (MultipleCompilationErrorsException e) {
            return compileError(e, logs);
        } catch (StackOverflowError e) {
            return failed("script", "The script called itself too deeply (stack overflow)", lineOf(e), logs);
        } catch (OutOfMemoryError e) {
            return failed("memory", "The script used more memory than a Code step may (128 MB)", null, logs);
        } catch (Throwable e) {
            // TimedInterrupt throws a (checked) TimeoutException from inside the script.
            if (e instanceof TimeoutException) {
                return timedOut(timeoutSeconds, logs.text());
            }
            return failed("script", describe(e), lineOf(e), logs);
        }
    }

    static CompilerConfiguration configuration(int timeoutSeconds) {
        CompilerConfiguration config = new CompilerConfiguration();
        // @Grab downloads and loads code while compiling, before any check below could run.
        config.setDisabledGlobalASTTransformations(Set.of("groovy.grape.GrabAnnotationTransformation"));
        config.addCompilationCustomizers(
                ScriptGuard.beforeTransforms(),
                ScriptGuard.afterResolving(),
                new ASTTransformationCustomizer(Map.of("value", (long) timeoutSeconds), TimedInterrupt.class));
        return config;
    }

    private static Map<String, Object> compileError(MultipleCompilationErrorsException e, LimitedWriter logs) {
        Message first = e.getErrorCollector().getErrorCount() > 0 ? e.getErrorCollector().getError(0) : null;
        if (first instanceof SyntaxErrorMessage s) {
            boolean blocked = s.getCause() instanceof ScriptGuard.Blocked;
            String message = blocked ? s.getCause().getOriginalMessage() : cleanSyntax(s.getCause().getOriginalMessage());
            return failed(blocked ? "blocked" : "script", message, s.getCause().getLine() > 0 ? s.getCause().getLine() : null, logs);
        }
        if (first instanceof ExceptionMessage m && m.getCause() != null) {
            return failed("script", describe(m.getCause()), null, logs);
        }
        return failed("script", "The script doesn't compile", null, logs);
    }

    // "unexpected token: } @ line 3, column 1." -> "unexpected token: }"
    private static String cleanSyntax(String message) {
        String m = message == null ? "Syntax error" : message.replaceAll("\\s*@ line \\d+, column \\d+\\.?\\s*$", "").trim();
        return m.isEmpty() ? "Syntax error" : Character.toUpperCase(m.charAt(0)) + m.substring(1);
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        // "No such property: amout for class: Script" -> "No such property: amout"
        message = message.replaceAll("\\s+for class: " + SCRIPT_NAME + "\\S*", "");
        if (e instanceof groovy.lang.MissingPropertyException || e instanceof groovy.lang.MissingMethodException
                || e instanceof NullPointerException || e instanceof ArithmeticException
                || e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            return message;
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    // The line in the user's script the error happened on, if it did happen there.
    private static Integer lineOf(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            for (StackTraceElement el : t.getStackTrace()) {
                if ((SCRIPT_NAME + ".groovy").equals(el.getFileName()) && el.getLineNumber() > 0) {
                    return el.getLineNumber();
                }
            }
        }
        return null;
    }

    private static Map<String, Object> timedOut(int timeoutSeconds, String logs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", "timeout");
        out.put("error", "The script ran longer than " + timeoutSeconds + " second" + (timeoutSeconds == 1 ? "" : "s"));
        out.put("logs", logs);
        return out;
    }

    private static Map<String, Object> failed(String kind, String error, Integer line, LimitedWriter logs) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("kind", kind);
        out.put("error", error);
        if (line != null) out.put("line", line);
        out.put("logs", logs.text());
        return out;
    }

    // What the script returned, made of JSON values only: text, numbers, true/false, null, maps
    // and lists. Dates become ISO 8601 text; anything else (a Closure, a custom object) is refused
    // with where it was, so the user knows what to convert.
    static Object jsonSafe(Object value, String where, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("The result is nested more than " + MAX_DEPTH + " levels deep");
        }
        if (value == null || value instanceof Boolean || value instanceof String) {
            return value;
        }
        if (value instanceof CharSequence || value instanceof Character) {
            return value.toString();
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite()) || value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            throw new IllegalArgumentException(capital(where) + " is a number that isn't finite (" + value + ")");
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Date d) {
            return d.toInstant().toString();
        }
        if (value instanceof Calendar c) {
            return c.toInstant().toString();
        }
        if (value instanceof ZonedDateTime z) {
            return z.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof OffsetDateTime || value instanceof TemporalAccessor) {
            return value.toString();
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, jsonSafe(e.getValue(), where.equals("the result") ? key : where + "." + key, depth + 1));
            }
            return out;
        }
        if (value instanceof Iterable<?> items) {
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object item : items) {
                if (out.size() >= 100_000) {
                    throw new IllegalArgumentException(capital(where) + " has more than 100000 items");
                }
                out.add(jsonSafe(item, where + "." + i++, depth + 1));
            }
            return out;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) {
                out.add(jsonSafe(Array.get(value, i), where + "." + i, depth + 1));
            }
            return out;
        }
        String type = value instanceof groovy.lang.Closure ? "a closure" : "a " + value.getClass().getSimpleName();
        throw new IllegalArgumentException(capital(where) + " is " + type
                + ", which can't be saved; use text, numbers, true/false, lists or maps");
    }

    private static String capital(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Keeps the first `limit` characters of what a script prints.
    static final class LimitedWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;
        private boolean cut;

        LimitedWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public synchronized void write(char[] buf, int off, int len) {
            int room = limit - text.length();
            if (len > room) {
                cut = true;
            }
            if (room > 0) {
                text.append(buf, off, Math.min(len, room));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        synchronized String text() {
            return cut ? text + "\n… (only the first " + limit + " characters are kept)" : text.toString();
        }
    }
}
