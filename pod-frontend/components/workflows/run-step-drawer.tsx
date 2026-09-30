"use client";

import { X } from "lucide-react";
import { AppLogo } from "@/components/ui/app-logo";
import { StepRunDetails } from "@/components/workflows/run-step-details";
import type { StepDetail } from "@/lib/types";
import type { WorkflowNode } from "@/lib/workflow-graph";

// One step of a run: what it received and returned, its status, timing and error. No settings:
// those belong to the workflow, not the run.
export function RunStepDrawer({
  node,
  stepNumber,
  step,
  runActive,
  now,
  onClose,
}: {
  node: WorkflowNode;
  stepNumber: number;
  // Null when the step has no record in this run.
  step: StepDetail | null;
  runActive: boolean;
  now: number;
  onClose: () => void;
}) {
  const isTrigger = node.data.kind === "trigger";
  const isCode = !!node.data.item && "handler" in node.data.item && node.data.item.handler === "code.groovy";
  return (
    <div className="flex h-full w-[400px] shrink-0 flex-col border-l border-border-strong bg-surface-raised" data-run-step-drawer>
      <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
        <div className="flex min-w-0 items-center gap-3">
          <AppLogo appId={node.data.app?.id} name={node.data.app?.name ?? "?"} />
          <div className="min-w-0">
            <p className="truncate text-sm font-bold">
              {stepNumber}. {node.data.item?.name ?? "Step"}
            </p>
            <p className="truncate text-xs text-text-muted">{node.data.app?.name}</p>
          </div>
        </div>
        <button
          onClick={onClose}
          aria-label="Close panel"
          className="flex size-7 shrink-0 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-white/10 hover:text-text"
        >
          <X className="size-4" />
        </button>
      </div>
      <div className="flex-1 overflow-y-auto p-5">
        {step ? (
          <StepRunDetails step={step} now={now} isTrigger={isTrigger} isCode={isCode} />
        ) : (
          <p className="text-sm text-text-muted">
            {runActive ? "This step hasn't started yet." : "This step didn't run in this run."}
          </p>
        )}
      </div>
    </div>
  );
}
