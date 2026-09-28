package com.bxtralabs.pod.backend.catalog;

import java.util.List;

// One app in the catalog (resources/catalog/apps.json). Ids are stable: saved workflow graphs
// refer to them (GraphNode.appId / itemId), so never rename one, only add.
// connectionOptional: steps of an app that pod-connector can connect may still run without an
// account (HTTP: most APIs need no credential). Otherwise the UI asks for one.
public record CatalogApp(
        String id,
        String name,
        String description,
        Boolean connectionOptional,
        List<Trigger> triggers,
        List<Action> actions
) {

    public CatalogApp {
        connectionOptional = connectionOptional != null && connectionOptional;
    }

    public record Trigger(String id, String name, String description, List<CatalogField> fields) {
    }

    // handler: which pod-processor handler runs it; saved as the step's GraphNode.type.
    public record Action(String id, String name, String description, String handler, List<CatalogField> fields) {
    }
}
