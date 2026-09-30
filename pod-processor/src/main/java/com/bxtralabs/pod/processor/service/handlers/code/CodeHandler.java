package com.bxtralabs.pod.processor.service.handlers.code;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

// type "code.groovy": runs the user's Groovy script in CodeSandbox.
// input: script (kept as written: templates aren't filled into code), inputs (name -> value, the
// values' templates filled in; each becomes a variable in the script), outputs (the schema the
// user declared: [{key, label, type, fields}]), timeoutSeconds (default 10, at most 30).
// output: the map the script returned, checked against the declared outputs, plus "logs": what
// it printed.
// A script error, a timeout or a result that doesn't match the outputs fails the step for good:
// the same script with the same data would fail again. A runner that can't start is retried.
@Component
@Order(1)
public class CodeHandler implements ActionHandler {

    public static final String TYPE = "code.groovy";
    public static final String LOGS = "logs";
    static final int DEFAULT_TIMEOUT = 10;
    static final int MAX_TIMEOUT = 30;
    private static final int LOGS_IN_ERROR = 1_000;

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    // Groovy's keywords, and the names the runner gives meaning to.
    static final Set<String> RESERVED = Set.of(
            "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "def", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "in", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "threadsafe", "throw", "throws", "trait", "transient", "true", "try", "var", "void",
            "volatile", "while", "yield", "record", "sealed", "permits", "non", "it", "out", "binding");

    private final CodeSandbox sandbox;

    public CodeHandler(CodeSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public boolean supports(GraphNode node) {
        return TYPE.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        if (!(input.get("script") instanceof String script) || script.isBlank()) {
            throw new PermanentStepException("The Code step has no script");
        }
        Map<String, Object> bindings = new LinkedHashMap<>();
        if (input.get("inputs") instanceof Map<?, ?> given) {
            for (Map.Entry<?, ?> e : given.entrySet()) {
                String name = String.valueOf(e.getKey());
                String problem = checkName(name);
                if (problem != null) throw new PermanentStepException(problem);
                bindings.put(name, e.getValue());
            }
        }
        List<Map<?, ?>> outputs = schema(input.get("outputs"));
        int timeout = timeout(input.get("timeoutSeconds"));

        CodeSandbox.Result r = sandbox.run(script, bindings, timeout);
        if (!r.ok()) {
            String where = r.line() == null ? "" : "Line " + r.line() + ": ";
            throw new PermanentStepException(where + r.error() + printed(r.logs()));
        }

        Object returned = r.result();
        if (returned == null) {
            returned = Map.of();
        }
        if (!(returned instanceof Map<?, ?> map)) {
            throw new PermanentStepException("The script must return a map of outputs, like return [total: 42]; it returned "
                    + kind(returned) + printed(r.logs()));
        }
        if (map.containsKey(LOGS)) {
            throw new PermanentStepException("\"logs\" holds what the script printed; return that output under another name");
        }
        List<String> problems = new ArrayList<>();
        check(outputs, map, "", problems);
        if (!problems.isEmpty()) {
            throw new PermanentStepException(String.join("; ", problems.subList(0, Math.min(3, problems.size())))
                    + (problems.size() > 3 ? " (and " + (problems.size() - 3) + " more)" : ""));
        }

        Map<String, Object> output = new LinkedHashMap<>();
        map.forEach((k, v) -> output.put(String.valueOf(k), v));
        output.put(LOGS, r.logs() == null ? "" : r.logs());
        return output;
    }

    // Null when the name can be a variable in the script.
    static String checkName(String name) {
        if (!NAME.matcher(name).matches()) {
            return "Input \"" + name + "\" can't be a variable name: use letters, digits and _, not starting with a digit";
        }
        if (RESERVED.contains(name)) {
            return "Input \"" + name + "\" is a word Groovy reserves; choose another name";
        }
        return null;
    }

    // Declared outputs a returned value doesn't match. Undeclared keys and missing values are fine:
    // declaring outputs lists what later steps can pick, it doesn't require every one.
    private static void check(List<Map<?, ?>> declared, Map<?, ?> values, String prefix, List<String> problems) {
        for (Map<?, ?> spec : declared) {
            String key = String.valueOf(spec.get("key"));
            Object value = values.get(key);
            if (value == null) continue;
            String name = prefix + key;
            String type = String.valueOf(spec.get("type"));
            List<Map<?, ?>> fields = schema(spec.get("fields"));
            switch (type) {
                case "text" -> expect(value instanceof String, name, "text", value, problems);
                case "number" -> expect(value instanceof Number, name, "a number", value, problems);
                case "boolean" -> expect(value instanceof Boolean, name, "true or false", value, problems);
                case "datetime" -> expect(value instanceof String s && isDateTime(s), name, "a date (ISO 8601 text)", value, problems);
                case "object" -> {
                    if (expect(value instanceof Map, name, "a map", value, problems) && !fields.isEmpty()) {
                        check(fields, (Map<?, ?>) value, name + ".", problems);
                    }
                }
                case "list" -> {
                    if (expect(value instanceof List, name, "a list", value, problems) && !fields.isEmpty()) {
                        List<?> items = (List<?>) value;
                        for (int i = 0; i < items.size(); i++) {
                            Object item = items.get(i);
                            if (item == null) continue;
                            if (expect(item instanceof Map, name + "." + i, "a map", item, problems)) {
                                check(fields, (Map<?, ?>) item, name + "." + i + ".", problems);
                            }
                        }
                    }
                }
                default -> {
                    // any
                }
            }
        }
    }

    private static boolean expect(boolean ok, String name, String wanted, Object got, List<String> problems) {
        if (!ok) {
            problems.add("Output " + name + " should be " + wanted + ", but the script returned " + kind(got));
        }
        return ok;
    }

    private static boolean isDateTime(String s) {
        for (var parse : List.<java.util.function.Consumer<String>>of(OffsetDateTime::parse, Instant::parse,
                LocalDateTime::parse, LocalDate::parse)) {
            try {
                parse.accept(s);
                return true;
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        return false;
    }

    private static String kind(Object value) {
        String preview = String.valueOf(value);
        preview = preview.length() > 40 ? preview.substring(0, 40) + "…" : preview;
        if (value instanceof String) return "text (\"" + preview + "\")";
        if (value instanceof Number) return "a number (" + preview + ")";
        if (value instanceof Boolean) return preview;
        if (value instanceof Map) return "a map";
        if (value instanceof List) return "a list";
        return preview;
    }

    // What the script printed, appended to its error so it's there to debug with.
    private static String printed(String logs) {
        if (logs == null || logs.isBlank()) return "";
        String tail = logs.length() > LOGS_IN_ERROR ? "…" + logs.substring(logs.length() - LOGS_IN_ERROR) : logs;
        return "\nPrinted before it stopped:\n" + tail.stripTrailing();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> schema(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return (List<Map<?, ?>>) (List<?>) list.stream().filter(o -> o instanceof Map<?, ?>).toList();
    }

    private static int timeout(Object value) {
        int t = DEFAULT_TIMEOUT;
        if (value instanceof Number n) t = n.intValue();
        else if (value instanceof String s) {
            try {
                t = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // default
            }
        }
        return Math.max(1, Math.min(t, MAX_TIMEOUT));
    }
}
