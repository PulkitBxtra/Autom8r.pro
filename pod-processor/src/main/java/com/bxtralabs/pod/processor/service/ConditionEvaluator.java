package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.graph.GraphEdge;
import org.springframework.stereotype.Component;

import java.util.Map;

// Decides whether a finished step's edge to a child is taken.
@Component
public class ConditionEvaluator {

    // TODO(2.4): evaluate edge.condition against the run's step outputs.
    // Until then every edge is taken, including ones with a condition.
    public boolean isTaken(GraphEdge edge, Map<String, StepRun> stepsByNode) {
        return true;
    }
}
