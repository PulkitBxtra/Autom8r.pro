package com.bxtralabs.pod.processor.service;

// Payload of a step-tasks message (JSON).
public record StepTaskMessage(String runId, String stepRunId) {
}
