package com.bxtralabs.pod.processor.service.template;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Fills {{ ... }} templates in step parameters and edge conditions from what the run has
// produced so far:
//   {{trigger.body.order.id}}      -> the webhook body
//   {{steps.<nodeId>.output.x}}    -> an earlier step's output
// Paths walk maps by key and lists by index ({{steps.a.output.items.0.name}}).
// A path that doesn't exist resolves to null (or "" inside text) instead of failing the step.
@Component
public class TemplateResolver {

    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");

    private final JsonMapper jsonMapper;

    public TemplateResolver(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    // The data templates read from: {"trigger": <trigger output>, "steps": {nodeId: {"output", "status"}}}.
    public static Map<String, Object> context(WorkflowGraph graph, Collection<StepRun> steps) {
        String triggerId = graph.nodes().stream()
                .filter(n -> GraphNode.KIND_TRIGGER.equals(n.kind()))
                .map(GraphNode::id)
                .findFirst()
                .orElse(null);

        Map<String, Object> stepsById = new HashMap<>();
        Object trigger = null;
        for (StepRun s : steps) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("output", s.getOutput());
            entry.put("status", s.getStatus() == null ? null : s.getStatus().name());
            stepsById.put(s.getNodeId(), entry);
            if (s.getNodeId().equals(triggerId)) {
                trigger = s.getOutput();
            }
        }

        Map<String, Object> context = new HashMap<>();
        context.put("trigger", trigger);
        context.put("steps", stepsById);
        return context;
    }

    // Resolves every template inside an arbitrary JSON-shaped value, returning a new value.
    public Object resolve(Object value, Map<String, Object> context) {
        if (value instanceof String s) {
            return resolveString(s, context);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolve(v, context)));
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(resolve(v, context)));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveParameters(Map<String, Object> parameters, Map<String, Object> context) {
        if (parameters == null) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) resolve(parameters, context);
    }

    private Object resolveString(String s, Map<String, Object> context) {
        Matcher whole = TEMPLATE.matcher(s);
        // Exactly one template and nothing else: keep the value's real type (number, map, list...).
        if (whole.matches()) {
            return lookup(whole.group(1), context);
        }

        Matcher m = TEMPLATE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(asText(lookup(m.group(1), context))));
        }
        m.appendTail(out);
        return out.toString();
    }

    static Object lookup(String path, Map<String, Object> context) {
        Object current = context;
        for (String part : path.split("\\.")) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(part);
            } else if (current instanceof List<?> list && isIndex(part) && Integer.parseInt(part) < list.size()) {
                current = list.get(Integer.parseInt(part));
            } else {
                return null;
            }
        }
        return current;
    }

    private String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return jsonMapper.writeValueAsString(value);
        }
        return String.valueOf(value);
    }

    private static boolean isIndex(String s) {
        return !s.isEmpty() && s.chars().allMatch(Character::isDigit) && s.length() < 10;
    }
}
