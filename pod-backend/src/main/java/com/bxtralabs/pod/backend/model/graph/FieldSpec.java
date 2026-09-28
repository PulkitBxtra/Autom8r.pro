package com.bxtralabs.pod.backend.model.graph;

import java.util.List;

// A step setting's rules, copied from the catalog onto each saved step (GraphNode.fields) so a
// run checks its input against the rules of the version it runs, even after the catalog changes.
// options: allowed values of a select, else null. secret: see CatalogField.
public record FieldSpec(String key, String label, String type, Boolean required, List<String> options,
                        Boolean secret) {

    // Absent in versions saved before the property existed: read as false instead of failing.
    public FieldSpec {
        required = required != null && required;
        secret = secret != null && secret;
    }
}
