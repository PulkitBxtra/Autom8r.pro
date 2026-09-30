import { backend, connector, webhooks, WEBHOOKS_URL } from "./client";
import type { TriggerStatus, Workflow, WorkflowGraph } from "@/lib/types";

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

// Saves a new version. baseVersionId is the version the edit started from: if someone saved
// since, pod-backend answers 409 instead of overwriting their change.
export function updateWorkflow(
  id: string,
  input: CreateWorkflowInput & { baseVersionId: string | null },
  token: string
) {
  return backend.put<Workflow>(`/workflow/${id}`, input, token);
}

// On/off for workflows with an app trigger; a 400 says why it couldn't be turned on.
export function setWorkflowActive(id: string, active: boolean, token: string) {
  return backend.put<Workflow>(`/workflow/${id}/active`, { active }, token);
}

// Is the trigger listening; null when nothing is registered (off, or a webhook trigger).
export function getTriggerStatus(workflowId: string, token: string) {
  return connector.get<TriggerStatus | null>(`/triggers/${workflowId}`, token);
}

export function triggerWorkflow(workflowId: string, payload: unknown = {}) {
  return webhooks.post<string>(`/trigger/${workflowId}`, payload);
}

// Where a Webhook-triggered workflow is called from outside: a POST whose JSON body (or a GET whose
// query parameters) becomes the trigger data. Runs the workflow's saved version.
export function webhookUrl(workflowId: string) {
  return `${WEBHOOKS_URL}/trigger/${workflowId}`;
}
