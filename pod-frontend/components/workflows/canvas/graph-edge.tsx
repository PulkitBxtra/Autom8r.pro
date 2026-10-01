"use client";

import { BaseEdge, type EdgeProps } from "@xyflow/react";
import { routePath, type EdgeRoute } from "@/lib/workflow-layout";
import { useCanvasActions } from "./canvas-actions-context";
import { EdgeLabels } from "./edge-labels";

export function GraphEdge({ id, sourceX, sourceY, targetX, targetY, selected, data }: EdgeProps) {
  const { interactive, onInsertNode } = useCanvasActions();
  const route = data?.route as EdgeRoute | undefined;
  const { path, x, labelY, addY } = routePath(sourceX, sourceY, targetX, targetY, route);

  return (
    <>
      <BaseEdge
        id={id}
        path={path}
        style={{
          stroke: selected ? "var(--color-lemon)" : "var(--color-border-strong)",
          strokeWidth: 2,
        }}
      />
      <EdgeLabels
        x={x}
        labelY={labelY}
        label={route?.label}
        addY={addY}
        onAdd={interactive ? () => onInsertNode(id) : undefined}
        addLabel="Insert step here"
      />
    </>
  );
}
