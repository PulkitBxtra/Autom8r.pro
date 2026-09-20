"use client";

import { BaseEdge, getSmoothStepPath, type EdgeProps } from "@xyflow/react";

// The dangling "not connected yet" stub from a node down to its
// add-a-step placeholder button -- dashed to read as pending, not a real
// connection.
export function PlaceholderEdge({
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
}: EdgeProps) {
  const [edgePath] = getSmoothStepPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
    borderRadius: 12,
  });

  return (
    <BaseEdge
      path={edgePath}
      style={{
        stroke: "var(--color-border-strong)",
        strokeWidth: 2,
        strokeDasharray: "5 5",
      }}
    />
  );
}
