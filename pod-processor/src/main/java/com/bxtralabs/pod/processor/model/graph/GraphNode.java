package com.bxtralabs.pod.processor.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

// Copy of pod-backend's GraphNode; keep the two in sync.
@JsonIgnoreProperties(ignoreUnknown = true)
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
        String connectionId,
        // Its settings' rules, stamped from the catalog on save. Null in graphs saved before that.
        List<FieldSpec> fields
) {
    public static final String KIND_TRIGGER = "trigger";
    public static final String KIND_ACTION = "action";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Position(double x, double y) {
    }
}
