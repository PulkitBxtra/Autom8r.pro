package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.graph.FieldSpec;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

// Checks a step's input once its {{...}} templates are filled in, against the rules pod-backend
// stamped on the step when the workflow was saved (GraphNode.fields). Save-time checks can't see
// what a template will produce; this catches a required value whose data was missing in this
// run, or a number field that received "abc", with a clear message instead of a confusing
// failure inside the handler. Numbers and true/false that arrive as text are converted.
// A failure is permanent: the same data would fail the same way on a retry.
@Component
public class StepInputChecker {

    public Map<String, Object> check(GraphNode node, Map<String, Object> input) throws PermanentStepException {
        if (node.fields() == null) {
            return input; // saved before rules were stamped
        }
        Map<String, Object> raw = node.parameters() == null ? Map.of() : node.parameters();
        Map<String, Object> out = new LinkedHashMap<>(input);
        for (FieldSpec f : node.fields()) {
            Object value = out.get(f.key());
            if (isEmpty(value)) {
                if (f.required() && !"boolean".equals(f.type())) {
                    throw new PermanentStepException(f.label() + " is empty" + fromTemplate(raw.get(f.key())));
                }
                continue;
            }
            out.put(f.key(), convert(f, value, raw.get(f.key())));
        }
        return out;
    }

    private static Object convert(FieldSpec f, Object value, Object raw) throws PermanentStepException {
        switch (f.type()) {
            case "number" -> {
                if (value instanceof Number) return value;
                if (value instanceof String s) {
                    try {
                        return number(new BigDecimal(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // reported below
                    }
                }
                throw new PermanentStepException(f.label() + " must be a number, but got " + preview(value) + fromTemplate(raw));
            }
            case "boolean" -> {
                if (value instanceof Boolean) return value;
                if (value instanceof String s && (s.trim().equalsIgnoreCase("true") || s.trim().equalsIgnoreCase("false"))) {
                    return Boolean.parseBoolean(s.trim());
                }
                throw new PermanentStepException(f.label() + " must be true or false, but got " + preview(value) + fromTemplate(raw));
            }
            case "select" -> {
                if (f.options() == null || f.options().contains(String.valueOf(value))) return value;
                throw new PermanentStepException(f.label() + " must be one of " + f.options() + ", but got " + preview(value));
            }
            default -> {
                return value;
            }
        }
    }

    private static Number number(BigDecimal d) {
        if (d.stripTrailingZeros().scale() <= 0) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException tooBig) {
                // falls back to a Double below
            }
        }
        return d.doubleValue();
    }

    // " (from {{trigger.body.x}})" when the setting was a template, so the user knows where to look.
    private static String fromTemplate(Object raw) {
        return raw instanceof String s && s.contains("{{") ? " (from " + s + ")" : "";
    }

    private static String preview(Object value) {
        String s = String.valueOf(value);
        return "\"" + (s.length() > 60 ? s.substring(0, 60) + "…" : s) + "\"";
    }

    private static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Map<?, ?> m && m.isEmpty());
    }
}
