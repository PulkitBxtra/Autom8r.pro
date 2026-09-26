import { backend, webhooks } from "./client";
import type { Workflow, WorkflowGraph } from "@/lib/types";

export function listWorkflows(token: string) {
  return backend.get<Workflow[]>("/workflows", token);
}

export function getWorkflow(id: string, token: string) {
  return backend.get<Workflow>(`/workflow/${id}`, token);
}

export type CreateWorkflowInput = {
  name: string;
  graph: WorkflowGraph;
};

// pod-backend validates the graph (single trigger, no cycles, every step
// connected) and stores it as version 1; a 400 carries a user-facing reason.
export function createWorkflow(input: CreateWorkflowInput, token: string) {
  return backend.post<Workflow>("/workflows", input, token);
}

export function triggerWorkflow(workflowId: string, payload: unknown = {}) {
  return webhooks.post<string>(`/trigger/${workflowId}`, payload);
}
