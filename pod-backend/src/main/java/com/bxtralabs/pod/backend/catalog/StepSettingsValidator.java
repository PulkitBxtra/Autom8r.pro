package com.bxtralabs.pod.backend.catalog;

import com.bxtralabs.pod.backend.model.graph.FieldSpec;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;

// Checks every step of a graph against the catalog on save and returns the graph as it should be
// stored: the step's app, name and handler come from the catalog (never from the client, so a
// step can't pick another app's handler), settings are type-checked, settings the event doesn't
// have are dropped, and each step carries its fields' rules for the run-time check.
// Throws IllegalArgumentException (a 400) with a message meant for the user.
@Component
public class StepSettingsValidator {

    static final int MAX_PARAMETERS_BYTES = 64 * 1024;

    private final CatalogService catalog;
    private final JsonMapper jsonMapper;

    public StepSettingsValidator(CatalogService catalog, JsonMapper jsonMapper) {
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
    }

    public WorkflowGraph normalize(WorkflowGraph graph) {
        List<GraphNode> nodes = graph.nodes().stream().map(this::normalize).toList();
        return new WorkflowGraph(nodes, graph.edges());
    }

    private GraphNode normalize(GraphNode node) {
        boolean trigger = GraphNode.KIND_TRIGGER.equals(node.kind());
        String step = "\"" + (node.name() == null || node.name().isBlank() ? node.id() : node.name()) + "\"";

        String itemName;
        String handler;
        List<CatalogField> fields;
        if (trigger) {
            CatalogApp.Trigger t = catalog.trigger(node.itemId()).orElseThrow(() -> unavailable(step, "trigger"));
            itemName = t.name();
            handler = null;
            fields = t.fields();
        } else {
            CatalogApp.Action a = catalog.action(node.itemId()).orElseThrow(() -> unavailable(step, "action"));
            itemName = a.name();
            handler = a.handler();
            fields = a.fields();
        }
        CatalogApp app = catalog.appOf(node.itemId()).orElseThrow();
        if (node.appId() != null && !node.appId().equals(app.id())) {
            throw new IllegalArgumentException("Step " + step + " doesn't belong to the app it's set up with");
        }

        // From here on, name the step as the catalog does.
        String named = "\"" + itemName + "\"";
        Map<String, Object> parameters = checkParameters(named, fields == null ? List.of() : fields,
                node.parameters() == null ? Map.of() : node.parameters());

        List<FieldSpec> specs = (fields == null ? List.<CatalogField>of() : fields).stream()
                .map(f -> new FieldSpec(f.key(), f.label(), f.type(), f.required(),
                        f.options() == null ? null : f.options().stream().map(CatalogField.Option::value).toList(),
                        f.secret()))
                .toList();

        return new GraphNode(node.id(), node.kind(), app.name(), node.itemId(), itemName, handler, parameters,
                node.position(), app.id(), node.connectionId(), specs);
    }

    private static IllegalArgumentException unavailable(String step, String what) {
        return new IllegalArgumentException("Step " + step + " uses a " + what + " that isn't available any more; choose another one");
    }

    private Map<String, Object> checkParameters(String step, List<CatalogField> fields, Map<String, Object> given) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CatalogField f : fields) {
            Object value = given.get(f.key());
            if (isEmpty(value)) {
                if (f.required() && !"boolean".equals(f.type())) {
                    throw new IllegalArgumentException("Step " + step + " needs " + f.label());
                }
                continue;
            }
            out.put(f.key(), checkValue(step, f, value));
        }
        // Settings the event doesn't have (left over from another event, or made up) are dropped.

        if (jsonMapper.writeValueAsString(out).length() > MAX_PARAMETERS_BYTES) {
            throw new IllegalArgumentException("Step " + step + " has too much in its settings (over 64 KB)");
        }
        return out;
    }

    private static Object checkValue(String step, CatalogField f, Object value) {
        String where = "Step " + step + ": " + f.label();
        return switch (f.type()) {
            case "text", "textarea" -> {
                if (!(value instanceof String)) throw new IllegalArgumentException(where + " must be text");
                yield value;
            }
            case "number" -> {
                if (value instanceof Number) yield value;
                if (value instanceof String s) {
                    if (isTemplate(s)) yield s;
                    try {
                        yield number(new BigDecimal(s.trim()));
                    } catch (NumberFormatException ignored) {
                        // falls through to the error below
                    }
                }
                throw new IllegalArgumentException(where + " must be a number");
            }
            case "boolean" -> {
                if (value instanceof Boolean || (value instanceof String s && isTemplate(s))) yield value;
                throw new IllegalArgumentException(where + " must be true or false");
            }
            case "select" -> {
                if (value instanceof String s && f.options().stream().anyMatch(o -> o.value().equals(s))) yield value;
                throw new IllegalArgumentException(where + " must be one of: "
                        + String.join(", ", f.options().stream().map(CatalogField.Option::label).toList()));
            }
            case "keyvalue" -> {
                if (value instanceof Map<?, ?> map && map.values().stream()
                        .allMatch(v -> v == null || v instanceof String || v instanceof Number || v instanceof Boolean)) {
                    yield value;
                }
                throw new IllegalArgumentException(where + " must be a list of names and values");
            }
            default -> value; // json: any JSON value
        };
    }

    // Whole numbers as Long, anything else as Double, like JSON numbers read back from storage.
    private static Number number(BigDecimal d) {
        // Not a ?: expression: that would widen the Long to a Double.
        if (d.stripTrailingZeros().scale() <= 0) {
            try {
                return d.longValueExact();
            } catch (ArithmeticException tooBig) {
                // falls back to a Double below
            }
        }
        return d.doubleValue();
    }

    // A value that's filled in when the run happens, e.g. "{{trigger.body.amount}}".
    static boolean isTemplate(String s) {
        return s.contains("{{") && s.contains("}}");
    }

    static boolean isEmpty(Object value) {
        return value == null
                || (value instanceof String s && s.isBlank())
                || (value instanceof Map<?, ?> m && m.isEmpty());
    }
}
