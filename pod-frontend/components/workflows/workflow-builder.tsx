"use client";

import { useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { StepPanel } from "@/components/workflows/canvas/step-panel";
import { useAuth } from "@/lib/auth-context";
import { createWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";
import {
  buildInitialGraph,
  flattenGraph,
  TRIGGER_NODE_ID,
} from "@/lib/workflow-graph";
import type { App, AppAction, AppTrigger } from "@/lib/types";

export function WorkflowBuilder() {
  const { token } = useAuth();
  const router = useRouter();
  const initial = buildInitialGraph();

  const [name, setName] = useState("Untitled workflow");
  const [nodes, setNodes, onNodesChange] = useNodesState(initial.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initial.edges);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const { trigger, orderedActionNodes, hasBranching } = flattenGraph(nodes, edges);
  const canSave = !!trigger?.data.item;

  const stepNumbers = useMemo(() => {
    const map = new Map<string, number>();
    map.set(TRIGGER_NODE_ID, 1);
    orderedActionNodes.forEach((n, i) => map.set(n.id, i + 2));
    return map;
  }, [orderedActionNodes]);

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  function handleSelectAppItem(app: App, item: AppTrigger | AppAction | undefined) {
    setNodes((nds) =>
      nds.map((n) =>
        n.id === selectedNodeId ? { ...n, data: { ...n.data, app, item } } : n
      )
    );
  }

  async function handleSave() {
    if (!token || !trigger?.data.item) return;
    setSaving(true);
    setError(null);
    try {
      const id = await createWorkflow(
        {
          name,
          triggerId: trigger.data.item.id,
          actions: orderedActionNodes
            .filter((n) => n.data.item && n.data.app)
            .map((n, i) => ({
              name: n.data.item!.name,
              type: "action",
              appName: n.data.app!.name,
              sortingOrder: i,
              parameters: {},
            })),
        },
        token
      );
      router.push(`/workflows/${id}`);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to save workflow");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="flex h-full">
      <div className="relative min-w-0 flex-1">
        <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start justify-between gap-4 p-5">
          <div className="pointer-events-auto max-w-sm rounded-2xl border border-border-strong bg-surface-raised/90 p-4 shadow-xl backdrop-blur">
            <Input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="h-auto border-none bg-transparent px-0 text-lg font-black focus:ring-0"
              placeholder="Workflow name"
            />
            <p className="mt-1 text-xs text-text-muted">
              Click the trigger to start, then use + to chain or branch actions.
            </p>
            {hasBranching && (
              <p className="mt-2 flex items-center gap-1.5 text-xs text-amber-400">
                <AlertTriangle className="size-3.5 shrink-0" />
                Branches save in a flattened order until multi-path workflows
                are supported.
              </p>
            )}
            {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
          </div>

          <Button
            onClick={handleSave}
            disabled={!canSave}
            loading={saving}
            className="pointer-events-auto"
          >
            Save workflow
          </Button>
        </div>

        <WorkflowCanvas
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          setNodes={setNodes}
          setEdges={setEdges}
          interactive
          selectedNodeId={selectedNodeId}
          onSelectNode={setSelectedNodeId}
        />
      </div>

      {selectedNode && (
        <StepPanel
          key={selectedNode.id}
          node={selectedNode}
          stepNumber={stepNumbers.get(selectedNode.id) ?? 1}
          onClose={() => setSelectedNodeId(null)}
          onSelectAppItem={handleSelectAppItem}
        />
      )}
    </div>
  );
}
