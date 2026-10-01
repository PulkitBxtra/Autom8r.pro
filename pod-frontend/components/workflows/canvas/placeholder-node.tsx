"use client";

import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Plus } from "lucide-react";
import type { PlaceholderNode as PlaceholderNodeType } from "@/lib/workflow-graph";
import { useCanvasActions } from "./canvas-actions-context";

export function PlaceholderNode({ data }: NodeProps<PlaceholderNodeType>) {
  const { onQuickAdd, onAddMerge } = useCanvasActions();

  return (
    <div className="relative flex size-8 items-center justify-center">
      <Handle type="target" position={Position.Top} className="!opacity-0" />
      {/* A nested Logic step's meeting point leads on to the outer one's. */}
      <Handle type="source" position={Position.Bottom} className="!opacity-0" />
      <button
        onClick={() => (data.mergeOf ? onAddMerge(data.mergeOf) : onQuickAdd(data.parentId, data.handle))}
        aria-label={data.mergeOf ? "Add step after paths" : "Add step"}
        className="flex size-8 items-center justify-center rounded-full border border-border-strong bg-surface-sunken text-text-muted transition-colors hover:border-lemon hover:text-lemon"
      >
        <Plus className="size-4" />
      </button>
    </div>
  );
}
