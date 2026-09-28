package com.bxtralabs.pod.processor.model.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

// Copy of pod-backend's FieldSpec; keep the two in sync.
@JsonIgnoreProperties(ignoreUnknown = true)
public record FieldSpec(String key, String label, String type, Boolean required, List<String> options,
                        Boolean secret) {

    // Absent in versions saved before the property existed: read as false instead of failing.
    public FieldSpec {
        required = required != null && required;
        secret = secret != null && secret;
    }
}
