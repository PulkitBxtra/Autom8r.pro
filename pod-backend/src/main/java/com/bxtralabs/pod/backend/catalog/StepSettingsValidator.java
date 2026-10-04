package com.bxtralabs.pod.backend.catalog;

import com.bxtralabs.pod.backend.model.graph.FieldSpec;
import com.bxtralabs.pod.backend.model.graph.GraphEdge;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

// Checks every step of a graph against the catalog on save and returns the graph as it should be
// stored: the step's app, name and handler come from the catalog (never from the client, so a
// step can't pick another app's handler), settings are type-checked, settings the event doesn't
// have are dropped, and each step carries its fields' rules for the run-time check.
// Throws IllegalArgumentException (a 400) with a message meant for the user.
@Component
public class StepSettingsValidator {

    static final int MAX_PARAMETERS_BYTES = 64 * 1024;
    static final int MAX_PATHS = 20;
    static final int MAX_CONDITIONS = 20;
    // Same operators as pod-processor's Conditions.
    static final Set<String> OPERATORS = Set.of(
            "equals", "not_equals", "contains", "not_contains", "starts_with", "ends_with",
            "gt", "gte", "lt", "lte", "is_empty", "is_not_empty", "is_true", "is_false", "in_list");
    static final Set<String> UNARY_OPERATORS = Set.of("is_empty", "is_not_empty", "is_true", "is_false");
    private static final Pattern PATH_ID = Pattern.compile("p_[a-z0-9]{1,16}");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern OUTPUT_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    // Groovy's keywords and the names the script runner uses. Same list as pod-processor's
    // CodeHandler.RESERVED; keep the two in sync.
    static final Set<String> RESERVED_VARIABLES = Set.of(
            "abstract", "as", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
            "def", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally", "float", "for",
            "goto", "if", "implements", "import", "in", "instanceof", "int", "interface", "long", "native", "new", "null",
            "package", "private", "protected", "public", "return", "short", "static", "strictfp", "super", "switch",
            "synchronized", "this", "threadsafe", "throw", "throws", "trait", "transient", "true", "try", "var", "void",
            "volatile", "while", "yield", "record", "sealed", "permits", "non", "it", "out", "binding");
    static final int MAX_DECLARED_OUTPUTS = 100;
    static final int MAX_OUTPUT_DEPTH = 3;
    // The outputs a Logic step's edges may leave from, besides its own path ids.
    static final String OTHERWISE = "otherwise";

    private final CatalogService catalog;
    private final JsonMapper jsonMapper;

    public StepSettingsValidator(CatalogService catalog, JsonMapper jsonMapper) {
        this.catalog = catalog;
        this.jsonMapper = jsonMapper;
    }

    public WorkflowGraph normalize(WorkflowGraph graph) {
        List<GraphNode> nodes = graph.nodes().stream().map(this::normalize).toList();
        return new WorkflowGraph(nodes, checkEdgeOutputs(nodes, graph.edges() == null ? List.of() : graph.edges()));
    }

    // Every edge out of a Logic step must leave from one of its outputs; edges out of other
    // steps have no output name (whatever the client sent is dropped).
    private List<GraphEdge> checkEdgeOutputs(List<GraphNode> nodes, List<GraphEdge> edges) {
        Map<String, GraphNode> byId = new HashMap<>();
        nodes.forEach(n -> byId.put(n.id(), n));
        List<GraphEdge> out = new ArrayList<>();
        for (GraphEdge e : edges) {
            GraphNode from = byId.get(e.from());
            Set<String> outputs = from == null ? null : logicOutputs(from);
            if (outputs == null) {
                out.add(e.sourceHandle() == null ? e : new GraphEdge(e.from(), e.to(), e.condition(), null));
                continue;
            }
            String handle = e.sourceHandle() == null && "logic.filter".equals(from.type()) ? "pass" : e.sourceHandle();
            if (handle == null || !outputs.contains(handle)) {
                throw new IllegalArgumentException("A connection leaves \"" + from.name()
                        + "\" from a path it doesn't have; connect it to one of its paths");
            }
            out.add(handle.equals(e.sourceHandle()) ? e : new GraphEdge(e.from(), e.to(), e.condition(), handle));
        }
        return out;
    }

    // A Logic step's output names (null for any other step).
    static Set<String> logicOutputs(GraphNode node) {
        if (node.type() == null || !node.type().startsWith("logic.")) {
            return null;
        }
        Set<String> outputs = new HashSet<>();
        switch (node.type()) {
            case "logic.if_else" -> outputs.addAll(List.of("if", "else"));
            case "logic.filter" -> outputs.add("pass");
            default -> {
                if (node.parameters() != null && node.parameters().get("paths") instanceof List<?> paths) {
                    paths.forEach(p -> outputs.add(String.valueOf(((Map<?, ?>) p).get("id"))));
                }
                if ("logic.switch".equals(node.type())) {
                    outputs.add(OTHERWISE);
                }
            }
        }
        return outputs;
    }

    private GraphNode normalize(GraphNode node) {
        boolean trigger = GraphNode.KIND_TRIGGER.equals(node.kind());
        String step = "\"" + (node.name() == null || node.name().isBlank() ? node.id() : node.name()) + "\"";

        String itemName;
        String handler;
        List<CatalogField> fields;
        CatalogApp.Action action = null;
        if (trigger) {
            CatalogApp.Trigger t = catalog.trigger(node.itemId()).orElseThrow(() -> unavailable(step, "trigger"));
            if (t.comingSoon()) throw comingSoon(node.itemId(), t.name());
            itemName = t.name();
            handler = null;
            fields = t.fields();
        } else {
            CatalogApp.Action a = catalog.action(node.itemId()).orElseThrow(() -> unavailable(step, "action"));
            if (a.comingSoon()) throw comingSoon(node.itemId(), a.name());
            action = a;
            itemName = a.name();
            handler = a.handler();
            fields = a.fields();
        }
        CatalogApp app = catalog.appOf(node.itemId()).orElseThrow();
        if (node.appId() != null && !node.appId().equals(app.id())) {
            throw new IllegalArgumentException("Step " + step + " doesn't belong to the app it's set up with");
        }
        String connectionId = node.connectionId() == null || node.connectionId().isBlank() ? null : node.connectionId();
        if (connectionId == null && CatalogApp.CONNECTION_REQUIRED.equals(app.connection())) {
            throw new IllegalArgumentException("Step \"" + itemName + "\" needs a " + app.name() + " account; choose one in the step's setup");
        }
        if (CatalogApp.CONNECTION_NONE.equals(app.connection())) {
            connectionId = null; // nothing to act through
        }

        // From here on, name the step as the catalog does.
        String named = "\"" + itemName + "\"";
        Map<String, Object> parameters = checkParameters(named, fields == null ? List.of() : fields,
                node.parameters() == null ? Map.of() : node.parameters());
        if (action != null && action.outputsFrom() != null && parameters.get(action.outputsFrom()) instanceof List<?> declared) {
            Set<String> builtIn = new HashSet<>();
            action.outputs().forEach(o -> builtIn.add(o.key()));
            for (Object o : declared) {
                String key = String.valueOf(((Map<?, ?>) o).get("key"));
                if (builtIn.contains(key)) {
                    throw new IllegalArgumentException("Step " + named + ": the output \"" + key
                            + "\" is already there (" + itemName + " adds it); give yours another name");
                }
            }
        }

        List<FieldSpec> specs = (fields == null ? List.<CatalogField>of() : fields).stream()
                .map(f -> new FieldSpec(f.key(), f.label(), f.type(), f.required(),
                        f.options() == null ? null : f.options().stream().map(CatalogField.Option::value).toList(),
                        f.secret()))
                .toList();

        return new GraphNode(node.id(), node.kind(), app.name(), node.itemId(), itemName, handler, parameters,
                node.position(), app.id(), connectionId, specs);
    }

    private static IllegalArgumentException unavailable(String step, String what) {
        return new IllegalArgumentException("Step " + step + " uses a " + what + " that isn't available any more; choose another one");
    }

    private IllegalArgumentException comingSoon(String itemId, String itemName) {
        String app = catalog.appOf(itemId).map(CatalogApp::name).orElse("This app");
        return new IllegalArgumentException(app + " \"" + itemName + "\" isn't available yet; choose another step or remove it");
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
            case "conditions" -> checkConditions(where, value);
            case "paths" -> checkPaths(where, value);
            case "code" -> {
                if (!(value instanceof String)) throw new IllegalArgumentException(where + " must be text");
                yield value;
            }
            case "variables" -> checkVariables(where, value);
            case "outputs" -> checkDeclaredOutputs(where, value, 1, new int[]{0});
            default -> value; // json: any JSON value
        };
    }

    private static Object checkConditions(String where, Object value) {
        if (!(value instanceof Map<?, ?> group)) {
            throw new IllegalArgumentException(where + " must be a set of conditions");
        }
        Object match = group.get("match");
        if (match != null && !"all".equals(match) && !"any".equals(match)) {
            throw new IllegalArgumentException(where + ": match must be all or any");
        }
        if (!(group.get("conditions") instanceof List<?> rows) || rows.isEmpty()) {
            throw new IllegalArgumentException(where + " needs at least one condition");
        }
        if (rows.size() > MAX_CONDITIONS) {
            throw new IllegalArgumentException(where + " has more than " + MAX_CONDITIONS + " conditions");
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> c)) {
                throw new IllegalArgumentException(where + " has a condition that isn't one");
            }
            String op = String.valueOf(c.get("op"));
            if (!OPERATORS.contains(op)) {
                throw new IllegalArgumentException(where + " has an unknown comparison: " + op);
            }
            if (isEmpty(c.get("left"))) {
                throw new IllegalArgumentException(where + " has a condition with nothing to check");
            }
            if (!UNARY_OPERATORS.contains(op) && isEmpty(c.get("right"))) {
                throw new IllegalArgumentException(where + " has a condition with nothing to compare to");
            }
        }
        return value;
    }

    // Each name becomes a variable in the script, so it must be one.
    private static Object checkVariables(String where, Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.values().stream()
                .allMatch(v -> v == null || v instanceof String || v instanceof Number || v instanceof Boolean)) {
            throw new IllegalArgumentException(where + " must be a list of names and values");
        }
        for (Object k : map.keySet()) {
            String name = String.valueOf(k);
            if (!VARIABLE.matcher(name).matches()) {
                throw new IllegalArgumentException(where + ": \"" + name
                        + "\" can't be a variable name; use letters, digits and _, not starting with a digit");
            }
            if (RESERVED_VARIABLES.contains(name)) {
                throw new IllegalArgumentException(where + ": \"" + name + "\" is a word Groovy reserves; choose another name");
            }
        }
        return value;
    }

    // Outputs the user declares, in the shape of catalog outputs. Stored with only the known
    // properties.
    private static List<Map<String, Object>> checkDeclaredOutputs(String where, Object value, int depth, int[] count) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(where + " must be a list of outputs");
        }
        Set<String> keys = new HashSet<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> o)) {
                throw new IllegalArgumentException(where + " has an output that isn't one");
            }
            if (++count[0] > MAX_DECLARED_OUTPUTS) {
                throw new IllegalArgumentException(where + " has more than " + MAX_DECLARED_OUTPUTS + " outputs");
            }
            String key = o.get("key") instanceof String k ? k.trim() : "";
            if (!OUTPUT_KEY.matcher(key).matches() || key.length() > 60) {
                throw new IllegalArgumentException(where + ": \"" + key
                        + "\" can't be an output name; use letters, digits and _, starting with a letter");
            }
            if (!keys.add(key)) {
                throw new IllegalArgumentException(where + " has the output \"" + key + "\" twice");
            }
            String type = String.valueOf(o.get("type"));
            if (!CatalogOutput.TYPES.contains(type)) {
                throw new IllegalArgumentException(where + ": output \"" + key + "\" has an unknown type " + type);
            }
            String label = o.get("label") instanceof String l && !l.isBlank() ? l.trim() : key;
            if (label.length() > 60) {
                throw new IllegalArgumentException(where + ": output \"" + key + "\" has a label over 60 characters");
            }
            Object fields = o.get("fields");
            boolean nested = fields instanceof List<?> f && !f.isEmpty();
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("key", key);
            clean.put("label", label);
            clean.put("type", type);
            if (nested) {
                if (!"object".equals(type) && !"list".equals(type)) {
                    throw new IllegalArgumentException(where + ": output \"" + key + "\" is " + type
                            + ", which can't hold fields; only object and list outputs can");
                }
                if (depth >= MAX_OUTPUT_DEPTH) {
                    throw new IllegalArgumentException(where + " nests outputs more than " + MAX_OUTPUT_DEPTH + " levels deep");
                }
                clean.put("fields", checkDeclaredOutputs(where + " (" + key + ")", fields, depth + 1, count));
            }
            out.add(clean);
        }
        return out;
    }

    private static Object checkPaths(String where, Object value) {
        if (!(value instanceof List<?> paths) || paths.isEmpty()) {
            throw new IllegalArgumentException(where + " needs at least one path");
        }
        if (paths.size() > MAX_PATHS) {
            throw new IllegalArgumentException(where + " has more than " + MAX_PATHS + " paths");
        }
        Set<String> ids = new HashSet<>();
        for (Object p : paths) {
            if (!(p instanceof Map<?, ?> path)) {
                throw new IllegalArgumentException(where + " has a path that isn't one");
            }
            String id = String.valueOf(path.get("id"));
            if (!PATH_ID.matcher(id).matches() || !ids.add(id)) {
                throw new IllegalArgumentException(where + " has a missing or repeated path id");
            }
            if (!(path.get("name") instanceof String name) || name.isBlank() || name.length() > 60) {
                throw new IllegalArgumentException(where + ": every path needs a name (up to 60 characters)");
            }
            checkConditions(where + " (" + name + ")", path);
        }
        return value;
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
                || (value instanceof Map<?, ?> m && m.isEmpty())
                || (value instanceof List<?> l && l.isEmpty());
    }
}
