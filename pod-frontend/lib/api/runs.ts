import { backend } from "./client";
import type { RunDetail, RunStatus, RunSummary } from "@/lib/types";

export function listRuns(workflowId: string, token: string, limit = 20) {
  return backend.get<RunSummary[]>(`/workflow/${workflowId}/runs?limit=${limit}`, token);
}

export function getRun(runId: string, token: string) {
  return backend.get<RunDetail>(`/run/${runId}`, token);
}

// Still moving: worth polling for updates.
export function isRunActive(status: RunStatus | undefined) {
  return status === "PENDING" || status === "RUNNING";
}

// A run can end (e.g. FAILED because one step failed) while a sibling step is
// still mid-call; that step's result is recorded when it finishes. Keep
// watching until no step is still running.
export function isRunSettling(detail: RunDetail | null | undefined) {
  if (!detail) return false;
  return isRunActive(detail.run.status) || detail.steps.some((s) => s.status === "RUNNING");
}
