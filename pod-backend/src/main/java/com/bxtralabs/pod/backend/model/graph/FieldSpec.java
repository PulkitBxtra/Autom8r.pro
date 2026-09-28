package com.bxtralabs.pod.backend.model.graph;

import java.util.List;

// A step setting's rules, copied from the catalog onto each saved step (GraphNode.fields) so a
// run checks its input against the rules of the version it runs, even after the catalog changes.
// options: allowed values of a select, else null.
public record FieldSpec(String key, String label, String type, boolean required, List<String> options) {
}
