"use client";

import { Handle, Position, type NodeProps } from "@xyflow/react";
import { Plus } from "lucide-react";
import type { PlaceholderNode as PlaceholderNodeType } from "@/lib/workflow-graph";
import { useCanvasActions } from "./canvas-actions-context";

export function PlaceholderNode({ data }: NodeProps<PlaceholderNodeType>) {
  const { onQuickAdd } = useCanvasActions();

  return (
    <div className="relative flex size-8 items-center justify-center">
      <Handle type="target" position={Position.Top} className="!opacity-0" />
      <button
        onClick={() => onQuickAdd(data.parentId)}
        aria-label="Add step"
        className="flex size-8 items-center justify-center rounded-full border border-border-strong bg-surface-sunken text-text-muted transition-colors hover:border-lemon hover:text-lemon"
      >
        <Plus className="size-4" />
      </button>
    </div>
  );
}
