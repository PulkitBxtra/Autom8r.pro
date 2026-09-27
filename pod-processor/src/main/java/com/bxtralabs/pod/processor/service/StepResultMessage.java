package com.bxtralabs.pod.processor.service;

import java.util.Map;

// Payload of a step-results message (JSON): what a worker reports after running a step.
// error == null means the step succeeded.
public record StepResultMessage(String runId, String stepRunId, Map<String, Object> input,
                                Map<String, Object> output, String error) {
}
