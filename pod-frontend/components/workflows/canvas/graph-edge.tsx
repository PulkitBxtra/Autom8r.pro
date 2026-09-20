"use client";

import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
  type EdgeProps,
} from "@xyflow/react";
import { Plus } from "lucide-react";
import { useCanvasActions } from "./canvas-actions-context";

export function GraphEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  selected,
}: EdgeProps) {
  const { interactive, onInsertNode } = useCanvasActions();
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 12,
  });

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        style={{
          stroke: selected ? "var(--color-lemon)" : "var(--color-border-strong)",
          strokeWidth: 2,
        }}
      />
      {interactive && (
        <EdgeLabelRenderer>
          <button
            onClick={(e) => {
              e.stopPropagation();
              onInsertNode(id);
            }}
            className="nodrag nopan absolute flex size-5 items-center justify-center rounded-full border border-border-strong bg-surface-sunken text-text-muted transition-colors hover:border-lemon hover:text-lemon"
            style={{
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: "all",
            }}
            aria-label="Insert step here"
          >
            <Plus className="size-3" />
          </button>
        </EdgeLabelRenderer>
      )}
    </>
  );
}
