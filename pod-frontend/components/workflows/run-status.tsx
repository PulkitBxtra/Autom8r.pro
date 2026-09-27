"use client";

import { createContext, useContext } from "react";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import type { RunStatus, StepDetail, StepStatus } from "@/lib/types";

type Tone = "neutral" | "lemon" | "success" | "danger" | "pending";

const RUN_STATUS: Record<RunStatus, { label: string; tone: Tone }> = {
  PENDING: { label: "Queued", tone: "pending" },
  RUNNING: { label: "Running", tone: "lemon" },
  SUCCEEDED: { label: "Succeeded", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
};

const STEP_STATUS: Record<StepStatus, { label: string; tone: Tone }> = {
  PENDING: { label: "Waiting", tone: "neutral" },
  READY: { label: "Queued", tone: "neutral" },
  RUNNING: { label: "Running", tone: "lemon" },
  RETRY_WAIT: { label: "Retrying", tone: "pending" },
  SUCCEEDED: { label: "Succeeded", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
  SKIPPED: { label: "Skipped", tone: "neutral" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
};

// Node border per step status on the canvas.
export const STEP_BORDER: Record<StepStatus, string> = {
  PENDING: "border-border-strong",
  READY: "border-border-strong",
  RUNNING: "border-lemon",
  RETRY_WAIT: "border-amber-500/60",
  SUCCEEDED: "border-emerald-500/60",
  FAILED: "border-red-500/70",
  SKIPPED: "border-border-strong",
  CANCELLED: "border-border-strong",
};

// Steps that never ran in this run are drawn faded.
export function stepDidNotRun(status: StepStatus) {
  return status === "SKIPPED" || status === "CANCELLED";
}

export function RunStatusBadge({ status, className }: { status: RunStatus; className?: string }) {
  const meta = RUN_STATUS[status] ?? { label: status, tone: "neutral" as Tone };
  return (
    <Badge tone={meta.tone} className={className}>
      {status === "RUNNING" && <span className="size-1.5 animate-pulse rounded-full bg-lemon" />}
      {meta.label}
    </Badge>
  );
}

export function StepStatusBadge({
  step,
  className,
}: {
  step: Pick<StepDetail, "status" | "attempt">;
  className?: string;
}) {
  const meta = STEP_STATUS[step.status] ?? { label: step.status, tone: "neutral" as Tone };
  // Which try this is, once a step has needed more than one. `attempt` counts
  // tries started so far, so while waiting to retry the next one is attempt + 1.
  const tryNumber = step.status === "RETRY_WAIT" ? step.attempt + 1 : step.attempt;
  const attempt = tryNumber > 1 ? ` · try ${tryNumber}` : "";
  return (
    <Badge tone={meta.tone} className={cn("px-2 py-0.5 text-[10px]", className)}>
      {step.status === "RUNNING" && <span className="size-1.5 animate-pulse rounded-full bg-lemon" />}
      {meta.label}
      {attempt}
    </Badge>
  );
}

// The selected run's steps by node id, for canvas nodes to read their status.
// null when no run is selected (the canvas then shows the plain workflow).
export const RunStepsContext = createContext<Map<string, StepDetail> | null>(null);

export function useRunStep(nodeId: string) {
  return useContext(RunStepsContext)?.get(nodeId) ?? null;
}
