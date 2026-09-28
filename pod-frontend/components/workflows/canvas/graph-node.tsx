"use client";

import { useEffect, useRef, useState } from "react";
import { Handle, Position, type NodeProps } from "@xyflow/react";
import { AlertTriangle, MoreVertical, Trash2, Zap } from "lucide-react";
import { cn } from "@/lib/utils";
import { AppLogo } from "@/components/ui/app-logo";
import type { WorkflowNode } from "@/lib/workflow-graph";
import { useCanvasActions } from "./canvas-actions-context";
import { useStepConnection } from "@/components/workflows/step-connections";
import { STEP_BORDER, StepStatusBadge, stepDidNotRun, useRunStep } from "@/components/workflows/run-status";

export function GraphNode({ id, data, selected }: NodeProps<WorkflowNode>) {
  const { interactive, selectedNodeId, onConfigure, onDelete } = useCanvasActions();
  // Set when a run is being viewed: how this step did in that run.
  const runStep = useRunStep(id);
  const isOpenInPanel = id === selectedNodeId;
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const empty = !data.item;
  const isTrigger = data.kind === "trigger";

  useEffect(() => {
    if (!menuOpen) return;
    function onPointerDown(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    return () => document.removeEventListener("mousedown", onPointerDown);
  }, [menuOpen]);

  return (
    <div className={cn("group relative w-64", runStep && stepDidNotRun(runStep.status) && "opacity-45")}>
      {runStep && (
        <div className="pointer-events-none absolute -top-2.5 right-3 z-10">
          <StepStatusBadge step={runStep} className="bg-surface shadow-md" />
        </div>
      )}

      {!isTrigger && (
        <Handle
          type="target"
          position={Position.Top}
          className="!size-2.5 !border-2 !border-surface !bg-border-strong"
        />
      )}

      <button
        onClick={() => onConfigure(id)}
        className={cn(
          "flex w-full items-center gap-3 rounded-2xl border p-3.5 text-left shadow-lg shadow-black/40 transition-colors",
          empty ? "border-dashed border-border-strong bg-surface-raised" : "border-border-strong bg-surface-raised",
          "hover:border-lemon/50",
          runStep && STEP_BORDER[runStep.status],
          runStep?.status === "RUNNING" && "shadow-lemon/20",
          (selected || isOpenInPanel) && "border-lemon"
        )}
      >
        {data.app ? (
          <AppLogo appId={data.app.id} name={data.app.name} className="size-10 rounded-xl" />
        ) : (
          <div
            className={cn(
              "flex size-10 shrink-0 items-center justify-center rounded-xl",
              empty ? "bg-white/5 text-text-faint" : "bg-lemon text-black"
            )}
          >
            <Zap className="size-4.5" />
          </div>
        )}
        <div className="min-w-0 flex-1">
          <p className="text-[10px] font-bold uppercase tracking-wide text-text-faint">
            {isTrigger ? "Trigger" : "Action"}
          </p>
          <p className="truncate text-sm font-bold">
            {data.item?.name ?? (isTrigger ? "Choose a trigger" : "Choose an action")}
          </p>
          {data.app && <AppLine appId={data.app.id} appName={data.app.name} connectionId={data.connectionId} />}
        </div>
      </button>

      {interactive && !isTrigger && (
        <div ref={menuRef} className="absolute -right-2 -top-2">
          <button
            onClick={() => setMenuOpen((v) => !v)}
            aria-label="Node options"
            className={cn(
              "flex size-6 items-center justify-center rounded-full border border-border-strong bg-surface text-text-faint opacity-0 transition-opacity hover:border-lemon hover:text-lemon group-hover:opacity-100 focus-visible:opacity-100",
              menuOpen && "opacity-100"
            )}
          >
            <MoreVertical className="size-3.5" />
          </button>

          {menuOpen && (
            <div className="absolute right-0 top-7 w-36 overflow-hidden rounded-xl border border-border-strong bg-surface-raised shadow-2xl">
              <button
                onClick={() => {
                  onDelete(id);
                  setMenuOpen(false);
                }}
                className="flex w-full items-center gap-2 px-3 py-2.5 text-sm text-red-400 transition-colors hover:bg-red-500/10"
              >
                <Trash2 className="size-3.5" />
                Delete step
              </button>
            </div>
          )}
        </div>
      )}

      <Handle
        type="source"
        position={Position.Bottom}
        className="!size-2.5 !border-2 !border-surface !bg-border-strong"
      />
    </div>
  );
}

// The app, plus which account the step uses, or what's wrong with it.
function AppLine({
  appId,
  appName,
  connectionId,
}: {
  appId: string;
  appName: string;
  connectionId: string | null | undefined;
}) {
  const { connector, chosen, missing, loading, error } = useStepConnection(appId, connectionId);
  let problem: string | null = null;
  if (connector && !loading && !error) {
    if (missing) problem = "Account removed";
    else if (!connectionId) problem = "Choose an account";
    else if (chosen?.status === "NEEDS_REAUTH") problem = "Account needs reconnecting";
  }

  if (problem) {
    return (
      <p className="flex items-center gap-1 truncate text-xs text-amber-400">
        <AlertTriangle className="size-3 shrink-0" />
        {problem}
      </p>
    );
  }
  return (
    <p className="truncate text-xs text-text-muted">
      {appName}
      {chosen?.label && ` · ${chosen.label}`}
    </p>
  );
}
