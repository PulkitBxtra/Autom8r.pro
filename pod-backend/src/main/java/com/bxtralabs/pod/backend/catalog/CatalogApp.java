package com.bxtralabs.pod.backend.catalog;

import java.util.List;

// One app in the catalog (resources/catalog/apps.json). Ids are stable: saved workflow graphs
// refer to them (GraphNode.appId / itemId), so never rename one, only add.
// connection: whether its steps act through one of the user's connections (pod-connector):
//   "required"  every step must have one chosen to be saved (GitHub, Slack...)
//   "optional"  may have one (HTTP: most APIs need no credential)
//   "none"      never (Webhook, Logic); the default
public record CatalogApp(
        String id,
        String name,
        String description,
        String connection,
        List<Trigger> triggers,
        List<Action> actions
) {

    public CatalogApp {
        connection = connection == null ? CONNECTION_NONE : connection;
    }

    public static final String CONNECTION_NONE = "none";
    public static final String CONNECTION_OPTIONAL = "optional";
    public static final String CONNECTION_REQUIRED = "required";

    public record Trigger(String id, String name, String description, List<CatalogField> fields) {
    }

    // handler: which pod-processor handler runs it; saved as the step's GraphNode.type.
    public record Action(String id, String name, String description, String handler, List<CatalogField> fields) {
    }
}
