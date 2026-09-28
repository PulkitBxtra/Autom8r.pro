package com.bxtralabs.pod.processor.model.graph;

import java.util.List;

// Copy of pod-backend's FieldSpec; keep the two in sync.
public record FieldSpec(String key, String label, String type, boolean required, List<String> options) {
}
