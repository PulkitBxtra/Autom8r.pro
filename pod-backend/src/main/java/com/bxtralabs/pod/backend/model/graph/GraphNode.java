package com.bxtralabs.pod.backend.model.graph;

import java.util.Map;

// One step in the graph: either the single trigger, or an action.
// itemId points at the catalog AppTrigger/AppAction; appName + type pick the handler at runtime.
public record GraphNode(
        String id,
        String kind,
        String appName,
        String itemId,
        String name,
        String type,
        Map<String, Object> parameters,
        Position position
) {
    public static final String KIND_TRIGGER = "trigger";
    public static final String KIND_ACTION = "action";

    // Canvas coordinates, kept only so the editor can restore the layout.
    public record Position(double x, double y) {
    }
}
