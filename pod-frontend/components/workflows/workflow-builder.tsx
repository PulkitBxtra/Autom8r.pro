"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { StepPanel, type StepTab } from "@/components/workflows/canvas/step-panel";
import { useAuth } from "@/lib/auth-context";
import { createWorkflow, updateWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";
import { useCatalog } from "@/lib/catalog-context";
import type { Workflow } from "@/lib/types";
import { logicOutputs } from "@/lib/logic";
import {
  buildGraphFromWorkflow,
  buildInitialGraph,
  graphSnapshot,
  reconcileOutputs,
  orderSteps,
  toWorkflowGraph,
  TRIGGER_NODE_ID,
  upstreamSources,
  type GraphNodeData,
} from "@/lib/workflow-graph";

// Creates a workflow, or with `existing`, edits one: saving then stores a new version of it.
export function WorkflowBuilder({ existing }: { existing?: Workflow }) {
  const { token } = useAuth();
  const router = useRouter();
  const catalog = useCatalog();
  const [initial] = useState(() => (existing ? buildGraphFromWorkflow(existing, catalog) : buildInitialGraph()));
  const [initialName] = useState(existing?.name ?? "Untitled workflow");
  const [initialSnapshot] = useState(() => graphSnapshot(initial.nodes, initial.edges));

  const [name, setName] = useState(initialName);
  const [nodes, setNodes, onNodesChange] = useNodesState(initial.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(initial.edges);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // After a save found missing settings: open that step on Configure and mark what's missing.
  const [panelTab, setPanelTab] = useState<StepTab | undefined>(undefined);
  const [showMissing, setShowMissing] = useState(false);

  const { trigger, orderedActionNodes } = orderSteps(nodes, edges);
  const dirty = name !== initialName || graphSnapshot(nodes, edges) !== initialSnapshot;
  const canSave = !!trigger?.data.item && (!existing || dirty);

  // Closing or reloading the tab with unsaved edits asks first.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  function handleCancel() {
    if (dirty && !window.confirm("Discard your changes?")) return;
    router.push(existing ? `/workflows/${existing.id}` : "/workflows");
  }

  const stepNumbers = useMemo(() => {
    const map = new Map<string, number>();
    map.set(TRIGGER_NODE_ID, 1);
    orderedActionNodes.forEach((n, i) => map.set(n.id, i + 2));
    return map;
  }, [orderedActionNodes]);

  const selectedNode = nodes.find((n) => n.id === selectedNodeId);
  const sources = useMemo(
    () => (selectedNodeId ? upstreamSources(selectedNodeId, nodes, edges, stepNumbers) : []),
    [selectedNodeId, nodes, edges, stepNumbers]
  );

  function selectNode(id: string | null) {
    setPanelTab(undefined);
    setSelectedNodeId(id);
  }

  function handleChangeStep(patch: Partial<GraphNodeData>) {
    // The last save's complaint may no longer hold; the next save re-checks.
    setError(null);
    const node = nodes.find((n) => n.id === selectedNodeId);
    if (!node) return;
    const data = { ...node.data, ...patch };
    setNodes((nds) => nds.map((n) => (n.id === node.id ? { ...n, data } : n)));
    setEdges((eds) => reconcileOutputs(eds, node.id, logicOutputs(data.item, data.parameters)));
  }

  async function handleSave() {
    if (!token || !trigger?.data.item) return;
    setError(null);

    const result = toWorkflowGraph(nodes, edges);
    if ("error" in result) {
      setError(result.error);
      // Open the offending step so the user can fix it straight away.
      setSelectedNodeId(result.nodeId);
      setPanelTab(result.tab);
      if (result.tab === "configure") setShowMissing(true);
      return;
    }

    setSaving(true);
    try {
      const workflow = existing
        ? await updateWorkflow(
            existing.id,
            { name, graph: result.graph, baseVersionId: existing.currentVersionId ?? null },
            token
          )
        : await createWorkflow({ name, graph: result.graph }, token);
      router.push(`/workflows/${workflow.id}`);
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
              {existing
                ? `Editing v${existing.version ?? 1}. Saving creates a new version; runs already going keep theirs.`
                : "Click the trigger to start, then use + to chain or branch actions."}
            </p>
            {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
          </div>

          <div className="pointer-events-auto flex items-center gap-2">
            <Button variant="ghost" onClick={handleCancel}>
              Cancel
            </Button>
            <Button onClick={handleSave} disabled={!canSave} loading={saving}>
              {existing ? (dirty ? "Save changes" : "No changes") : "Save workflow"}
            </Button>
          </div>
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
          onSelectNode={selectNode}
        />
      </div>

      {selectedNode && (
        <StepPanel
          key={`${selectedNode.id}:${panelTab ?? ""}`}
          node={selectedNode}
          stepNumber={stepNumbers.get(selectedNode.id) ?? 1}
          sources={sources}
          initialTab={panelTab}
          showMissing={showMissing}
          onClose={() => selectNode(null)}
          onChange={handleChangeStep}
        />
      )}
    </div>
  );
}
