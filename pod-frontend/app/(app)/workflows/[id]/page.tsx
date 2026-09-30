"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { ArrowLeft, History, Pencil, Play } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { FullPageSpinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { StepPanel } from "@/components/workflows/canvas/step-panel";
import { StepConnectionsProvider } from "@/components/workflows/step-connections";
import { TriggerSwitch } from "@/components/workflows/trigger-switch";
import { useCatalog } from "@/lib/catalog-context";
import { useAuth } from "@/lib/auth-context";
import { getWorkflow, triggerWorkflow } from "@/lib/api/workflows";
import {
  buildGraphFromWorkflow,
  upstreamSources,
  countActions,
  orderSteps,
  TRIGGER_NODE_ID,
} from "@/lib/workflow-graph";
import { ApiError } from "@/lib/api/client";
import type { Workflow } from "@/lib/types";

// A saved workflow: its steps on the canvas (each step's settings in the drawer), switching it
// on, running it, and the way to its runs.
export default function WorkflowDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const { token } = useAuth();
  const router = useRouter();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- resets loading on id/token change before the fetch settles
    setLoading(true);
    getWorkflow(id, token)
      .then(setWorkflow)
      .catch((err) =>
        setError(err instanceof ApiError ? err.message : "Failed to load workflow")
      )
      .finally(() => setLoading(false));
  }, [id, token]);

  const catalog = useCatalog();
  // An app trigger (GitHub, Slack...) gets the on/off switch; the Webhook trigger is always on.
  const appTrigger = workflow?.graph?.nodes.find((n) => n.kind === "trigger" && n.appId && n.appId !== "app_webhook") ?? null;
  const graph = useMemo(() => {
    // Wait for the catalog so steps don't flash their stored fallback names first.
    if (!workflow || catalog.loading) return { nodes: [], edges: [] };
    return buildGraphFromWorkflow(workflow, catalog);
  }, [workflow, catalog]);
  const [nodes, setNodes, onNodesChange] = useNodesState(graph.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(graph.edges);

  // useNodesState only reads its argument on the first render, before the workflow has loaded.
  useEffect(() => {
    setNodes(graph.nodes);
    setEdges(graph.edges);
  }, [graph, setNodes, setEdges]);

  const { orderedActionNodes } = orderSteps(nodes, edges);
  const stepNumbers = useMemo(() => {
    const map = new Map<string, number>();
    map.set(TRIGGER_NODE_ID, 1);
    orderedActionNodes.forEach((n, i) => map.set(n.id, i + 2));
    return map;
  }, [orderedActionNodes]);
  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  // Starts a run and opens it, to watch it execute.
  async function handleRun() {
    setRunning(true);
    setRunError(null);
    try {
      const runId = await triggerWorkflow(id, { source: "manual-test" });
      router.push(`/workflows/${id}/runs/${runId}`);
    } catch (err) {
      setRunError(err instanceof ApiError ? err.message : "Failed to trigger workflow");
      setRunning(false);
    }
  }

  return (
    <StepConnectionsProvider>
      <Topbar title={workflow?.name ?? "Workflow"} />

      {loading && <FullPageSpinner />}

      {!loading && error && (
        <div className="p-6">
          <EmptyState icon={History} title="Couldn't load workflow" description={error} />
        </div>
      )}

      {!loading && workflow && (
        <div className="flex min-h-0 flex-1">
          <div className="relative min-w-0 flex-1">
            <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start justify-between gap-4 p-5">
              <div className="pointer-events-auto max-w-sm rounded-2xl border border-border-strong bg-surface-raised/90 p-4 shadow-xl backdrop-blur">
                <Link
                  href="/workflows"
                  className="mb-2 inline-flex items-center gap-1.5 text-xs font-medium text-text-muted hover:text-text"
                >
                  <ArrowLeft className="size-3.5" />
                  All workflows
                </Link>
                <h2 className="text-lg font-black">{workflow.name}</h2>
                <p className="mt-1 text-xs text-text-muted">
                  {countActions(workflow) + 1} steps
                  {workflow.version != null && <> · v{workflow.version}</>}
                </p>

                {appTrigger && (
                  <TriggerSwitch workflow={workflow} trigger={appTrigger} onChange={setWorkflow} />
                )}
                {runError && <p className="mt-2 text-xs text-red-400">{runError}</p>}
              </div>

              <div className="pointer-events-auto flex items-center gap-2">
                <Button href={`/workflows/${workflow.id}/edit`} variant="outline">
                  <Pencil className="size-4" />
                  Edit
                </Button>
                <Button href={`/workflows/${workflow.id}/runs`} variant="outline">
                  <History className="size-4" />
                  Runs
                </Button>
                <Button onClick={handleRun} loading={running} variant="secondary">
                  <Play className="size-4" />
                  Run now
                </Button>
              </div>
            </div>

            <WorkflowCanvas
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              setNodes={() => {}}
              setEdges={() => {}}
              interactive={false}
              selectedNodeId={selectedNodeId}
              onSelectNode={setSelectedNodeId}
            />
          </div>

          {selectedNode && (
            <StepPanel
              key={selectedNode.id}
              node={selectedNode}
              stepNumber={stepNumbers.get(selectedNode.id) ?? 1}
              sources={upstreamSources(selectedNode.id, nodes, edges, stepNumbers)}
              readOnly
              workflow={{ id: workflow.id, version: workflow.version ?? null }}
              onClose={() => setSelectedNodeId(null)}
            />
          )}
        </div>
      )}
    </StepConnectionsProvider>
  );
}
