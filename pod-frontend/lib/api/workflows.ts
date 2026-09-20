import { backend, webhooks } from "./client";
import type { Action, Workflow } from "@/lib/types";

export function listWorkflows(token: string) {
  return backend.get<Workflow[]>("/workflows", token);
}

export function getWorkflow(id: string, token: string) {
  return backend.get<Workflow>(`/workflow/${id}`, token);
}

export type ActionInput = Omit<Action, "id">;

export type CreateWorkflowInput = {
  name: string;
  triggerId: string;
  actions: ActionInput[];
};

// Workflow creation is owned by pod-webhooks -- it's what establishes
// ownership via the caller's JWT -- not pod-backend, which is read-only here.
export function createWorkflow(input: CreateWorkflowInput, token: string) {
  return webhooks.post<string>("/create/workflow", input, token);
}

export function triggerWorkflow(workflowId: string, payload: unknown = {}) {
  return webhooks.post<string>(`/trigger/${workflowId}`, payload);
}
