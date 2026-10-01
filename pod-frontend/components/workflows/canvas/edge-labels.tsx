"use client";

import { EdgeLabelRenderer } from "@xyflow/react";
import { Plus } from "lucide-react";

// A path's name and the + that adds a step on a line, drawn over the canvas at flow coordinates.
export function EdgeLabels({
  x,
  labelY,
  label,
  addY,
  onAdd,
  addLabel,
}: {
  x: number;
  labelY?: number;
  label?: string;
  addY: number;
  onAdd?: () => void;
  addLabel: string;
}) {
  return (
    <EdgeLabelRenderer>
      {label && labelY !== undefined && (
        <span
          className="nodrag nopan pointer-events-none absolute max-w-28 truncate rounded-full border border-border-strong bg-surface px-2 py-0.5 text-[10px] font-semibold text-text-muted"
          style={{ transform: `translate(-50%, -50%) translate(${x}px, ${labelY}px)` }}
        >
          {label}
        </span>
      )}
      {onAdd && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onAdd();
          }}
          className="nodrag nopan absolute flex size-5 items-center justify-center rounded-full border border-border-strong bg-surface-sunken text-text-muted transition-colors hover:border-lemon hover:text-lemon"
          style={{ transform: `translate(-50%, -50%) translate(${x}px, ${addY}px)`, pointerEvents: "all" }}
          aria-label={addLabel}
        >
          <Plus className="size-3" />
        </button>
      )}
    </EdgeLabelRenderer>
  );
}
