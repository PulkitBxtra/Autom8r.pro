package com.bxtralabs.pod.backend.catalog;

import com.bxtralabs.pod.backend.model.graph.FieldSpec;
import com.bxtralabs.pod.backend.model.graph.GraphNode;
import com.bxtralabs.pod.backend.model.graph.WorkflowGraph;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

// Keeps secret step settings from being shown again once saved. Graphs and run inputs leave this
// pod with secret values replaced by MASK; a save that still carries MASK means "unchanged" and
// gets the stored value back. Which settings are secret comes from the fields stamped on the step,
// or from the catalog for steps saved before stamping. Values are still stored as typed: real
// credentials belong in a connection (encrypted by pod-connector), this only stops re-display.
@Component
public class SecretMasker {

    public static final String MASK = "••••••••";
    // Header/key names whose values are treated as secret in a secret keyvalue field.
    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(auth|cookie|token|secret|passw|api[-_]?key|private|session|signature).*");
    // A value that is only a template ("{{trigger.body.key}}") names where data comes from, not a secret.
    private static final Pattern ONLY_TEMPLATE = Pattern.compile("\\s*\\{\\{[^{}]+}}\\s*");

    private final CatalogService catalog;

    public SecretMasker(CatalogService catalog) {
        this.catalog = catalog;
    }

    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).matches();
    }

    // The graph as it may be shown: literal secret values replaced by MASK.
    public WorkflowGraph mask(WorkflowGraph graph) {
        if (graph == null || graph.nodes() == null) {
            return graph;
        }
        boolean changed = false;
        List<GraphNode> nodes = new ArrayList<>();
        for (GraphNode n : graph.nodes()) {
            Map<String, Object> masked = maskValues(secretFields(n), n.parameters(), false);
            changed |= masked != n.parameters();
            nodes.add(masked == n.parameters() ? n : withParameters(n, masked));
        }
        // Nothing secret: hand back the graph itself rather than a copy.
        return changed ? new WorkflowGraph(nodes, graph.edges()) : graph;
    }

    // A step's run input (templates already filled in) as it may be shown: every secret value hidden.
    public Map<String, Object> maskInput(GraphNode node, Map<String, Object> input) {
        return node == null ? input : maskValues(secretFields(node), input, true);
    }

    // Puts stored values back where a save still has MASK. previous: the graph being replaced, or
    // null for a new workflow (where MASK can't mean anything).
    public WorkflowGraph restore(WorkflowGraph incoming, WorkflowGraph previous) {
        Map<String, GraphNode> before = new HashMap<>();
        if (previous != null && previous.nodes() != null) {
            previous.nodes().forEach(n -> before.put(n.id(), n));
        }
        List<GraphNode> nodes = incoming.nodes().stream().map(n -> {
            if (n.parameters() == null || !containsMask(n.parameters())) {
                return n;
            }
            GraphNode old = before.get(n.id());
            Map<String, Object> oldParams = old == null || old.parameters() == null ? Map.of() : old.parameters();
            Map<String, Object> params = new LinkedHashMap<>(n.parameters());
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (MASK.equals(e.getValue())) {
                    e.setValue(stored(n, e.getKey(), oldParams.get(e.getKey())));
                } else if (e.getValue() instanceof Map<?, ?> map && map.containsValue(MASK)) {
                    Map<?, ?> oldMap = oldParams.get(e.getKey()) instanceof Map<?, ?> m ? m : Map.of();
                    Map<Object, Object> merged = new LinkedHashMap<>(map);
                    for (Map.Entry<Object, Object> kv : merged.entrySet()) {
                        if (MASK.equals(kv.getValue())) {
                            kv.setValue(stored(n, e.getKey() + " " + kv.getKey(), oldMap.get(kv.getKey())));
                        }
                    }
                    e.setValue(merged);
                }
            }
            return withParameters(n, params);
        }).toList();
        return new WorkflowGraph(nodes, incoming.edges());
    }

    private static Object stored(GraphNode node, String what, Object value) {
        if (value == null) {
            String step = node.name() == null ? node.id() : node.name();
            throw new IllegalArgumentException("Step \"" + step + "\": enter " + what + " again; the saved value isn't available");
        }
        return value;
    }

    private static boolean containsMask(Map<String, Object> params) {
        return params.values().stream().anyMatch(v -> MASK.equals(v) || (v instanceof Map<?, ?> m && m.containsValue(MASK)));
    }

    private List<FieldSpec> secretFields(GraphNode node) {
        if (node.fields() != null) {
            return node.fields().stream().filter(FieldSpec::secret).toList();
        }
        // Saved before rules were stamped: go by the catalog as it is now.
        List<CatalogField> fields = GraphNode.KIND_TRIGGER.equals(node.kind())
                ? catalog.trigger(node.itemId()).map(CatalogApp.Trigger::fields).orElse(List.of())
                : catalog.action(node.itemId()).map(CatalogApp.Action::fields).orElse(List.of());
        return fields == null ? List.of() : fields.stream()
                .filter(CatalogField::secret)
                .map(f -> new FieldSpec(f.key(), f.label(), f.type(), f.required(), null, true))
                .toList();
    }

    // resolved: values are run input (hide everything secret); otherwise saved settings, where a
    // value that is only a template isn't secret and stays visible.
    private static Map<String, Object> maskValues(List<FieldSpec> secret, Map<String, Object> values, boolean resolved) {
        if (secret.isEmpty() || values == null) {
            return values;
        }
        Map<String, Object> out = new LinkedHashMap<>(values);
        for (FieldSpec f : secret) {
            Object v = out.get(f.key());
            if ("keyvalue".equals(f.type()) && v instanceof Map<?, ?> map) {
                Map<Object, Object> masked = new LinkedHashMap<>(map);
                masked.replaceAll((k, val) -> isSensitiveKey(String.valueOf(k)) && hides(val, resolved) ? MASK : val);
                out.put(f.key(), masked);
            } else if (hides(v, resolved)) {
                out.put(f.key(), MASK);
            }
        }
        return out;
    }

    private static boolean hides(Object value, boolean resolved) {
        if (value == null || (value instanceof String s && s.isEmpty())) {
            return false;
        }
        return resolved || !(value instanceof String s && ONLY_TEMPLATE.matcher(s).matches());
    }

    private static GraphNode withParameters(GraphNode n, Map<String, Object> parameters) {
        return new GraphNode(n.id(), n.kind(), n.appName(), n.itemId(), n.name(), n.type(), parameters,
                n.position(), n.appId(), n.connectionId(), n.fields());
    }
}
