package com.bxtralabs.pod.processor.service;

import java.util.List;

// Published by Orchestrator inside its transaction; StepDispatcher only acts on it
// after that transaction commits, so a worker never sees a step that isn't READY yet.
public record StepsReadyEvent(String runId, List<String> stepRunIds) {
}
