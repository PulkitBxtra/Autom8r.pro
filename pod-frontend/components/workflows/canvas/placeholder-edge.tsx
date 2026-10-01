"use client";

import { BaseEdge, type EdgeProps } from "@xyflow/react";
import { routePath, type EdgeAdd, type EdgeRoute } from "@/lib/workflow-layout";
import { useCanvasActions } from "./canvas-actions-context";
import { EdgeLabels } from "./edge-labels";

// A line that isn't a connection yet -- dashed to read as pending: down to an add-step button,
// or from the end of a path to where a Logic step's paths will meet. The + on the latter adds
// a step at the end of that path.
export function PlaceholderEdge({ sourceX, sourceY, targetX, targetY, data }: EdgeProps) {
  const { onQuickAdd, onAddMerge } = useCanvasActions();
  const route = data?.route as EdgeRoute | undefined;
  const add = data?.add as EdgeAdd | undefined;
  const { path, x, labelY, addY } = routePath(sourceX, sourceY, targetX, targetY, route);

  return (
    <>
      <BaseEdge
        path={path}
        style={{
          stroke: "var(--color-border-strong)",
          strokeWidth: 2,
          strokeDasharray: "5 5",
        }}
      />
      <EdgeLabels
        x={x}
        labelY={labelY}
        label={route?.label}
        addY={addY}
        onAdd={
          add
            ? () => ("mergeOf" in add ? onAddMerge(add.mergeOf) : onQuickAdd(add.parentId, add.handle))
            : undefined
        }
        addLabel="Add step to this path"
      />
    </>
  );
}
