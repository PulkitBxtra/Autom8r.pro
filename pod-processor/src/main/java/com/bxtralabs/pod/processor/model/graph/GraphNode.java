package com.bxtralabs.pod.processor.model.graph;

import java.util.Map;

// Copy of pod-backend's GraphNode; keep the two in sync.
public record GraphNode(
        String id,
        String kind,
        String appName,
        String itemId,
        String name,
        String type,
        Map<String, Object> parameters,
        Position position,
        // Catalog app id (app_github, ...), which a connection is tied to. Null in graphs saved
        // before steps could use connections.
        String appId,
        // The user's connection (pod-connector) this step acts through; null if it needs none.
        String connectionId
) {
    public static final String KIND_TRIGGER = "trigger";
    public static final String KIND_ACTION = "action";

    public record Position(double x, double y) {
    }
}
