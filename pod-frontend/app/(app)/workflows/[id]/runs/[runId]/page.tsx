"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { ArrowLeft, History, Play } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { FullPageSpinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { RunStepDrawer } from "@/components/workflows/run-step-drawer";
import { RunStatusBadge, RunStepsContext } from "@/components/workflows/run-status";
import { useCatalog } from "@/lib/catalog-context";
import { useWorkflow } from "@/hooks/use-workflow";
import { useRun } from "@/hooks/use-runs";
import { useNow } from "@/hooks/use-now";
import { isRunActive, isRunSettling } from "@/lib/api/runs";
import { triggerWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";
import { buildGraphFromWorkflow, orderSteps, TRIGGER_NODE_ID } from "@/lib/workflow-graph";
import { formatDuration, formatRelativeTime } from "@/lib/utils";

// One run: the steps of the version it ran, each marked with what happened, and for the step
// clicked, what it received and returned.
export default function RunPage({ params }: { params: Promise<{ id: string; runId: string }> }) {
  const { id, runId } = use(params);
  const router = useRouter();
  const catalog = useCatalog();
  const { workflow, error: workflowError, loading: workflowLoading } = useWorkflow(id);
  const { detail, error: runError, loading: runLoading } = useRun(runId);
  const now = useNow(isRunSettling(detail));
  // undefined: nothing picked yet (see autoNodeId); null: the drawer was closed.
  const [picked, setPicked] = useState<string | null | undefined>(undefined);
  const [running, setRunning] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  // Draw the graph this run executed (it may be an older version than the workflow's current
  // one). Compared as JSON so each poll doesn't rebuild the canvas.
  const graphJson = detail ? JSON.stringify(detail.graph ?? workflow?.graph ?? null) : null;
  const graph = useMemo(() => {
    if (!workflow || !graphJson || catalog.loading) return { nodes: [], edges: [] };
    const g = JSON.parse(graphJson);
    return buildGraphFromWorkflow(g ? { ...workflow, graph: g } : workflow, catalog);
  }, [workflow, graphJson, catalog]);
  const [nodes, setNodes, onNodesChange] = useNodesState(graph.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(graph.edges);
  useEffect(() => {
    setNodes(graph.nodes);
    setEdges(graph.edges);
  }, [graph, setNodes, setEdges]);

  const runSteps = useMemo(() => (detail ? new Map(detail.steps.map((s) => [s.nodeId, s])) : null), [detail]);

  // Until a step is picked (or the drawer closed), show the step that failed, or else what
  // started the run; so a run watched live jumps to its failure.
  const failedNodeId = detail?.steps.find((s) => s.status === "FAILED")?.nodeId ?? null;
  const autoNodeId =
    failedNodeId && nodes.some((n) => n.id === failedNodeId)
      ? failedNodeId
      : (nodes.find((n) => n.data.kind === "trigger")?.id ?? null);
  const selectedNodeId = picked === undefined ? autoNodeId : picked;

  const { orderedActionNodes } = orderSteps(nodes, edges);
  const stepNumbers = useMemo(() => {
    const map = new Map<string, number>();
    map.set(TRIGGER_NODE_ID, 1);
    orderedActionNodes.forEach((n, i) => map.set(n.id, i + 2));
    return map;
  }, [orderedActionNodes]);
  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  async function runAgain() {
    setRunning(true);
    setStartError(null);
    try {
      const next = await triggerWorkflow(id, { source: "manual-test" });
      router.push(`/workflows/${id}/runs/${next}`);
    } catch (err) {
      setStartError(err instanceof ApiError ? err.message : "Failed to trigger workflow");
      setRunning(false);
    }
  }

  const run = detail?.run;
  const active = !!run && isRunActive(run.status);
  const duration =
    run?.startTimestamp != null
      ? (run.endTimestamp ?? (active ? now : run.startTimestamp)) - run.startTimestamp
      : null;
  const olderVersion = run?.version != null && workflow?.version != null && run.version !== workflow.version;
  const failed = workflowError ?? (!detail ? runError : null);

  return (
    <>
      <Topbar title={workflow ? `${workflow.name} · Run ${runId.slice(-6)}` : "Run"} />
      {failed ? (
        <div className="p-6">
          <EmptyState icon={History} title="Couldn't load this run" description={failed} />
        </div>
      ) : workflowLoading || runLoading || !workflow || !detail ? (
        <FullPageSpinner />
      ) : (
        <div className="flex min-h-0 flex-1">
          <div className="relative min-w-0 flex-1">
            <div className="pointer-events-none absolute inset-x-0 top-0 z-10 flex items-start justify-between gap-4 p-5">
              <div className="pointer-events-auto max-w-sm rounded-2xl border border-border-strong bg-surface-raised/90 p-4 shadow-xl backdrop-blur">
                <Link
                  href={`/workflows/${id}/runs`}
                  className="mb-2 inline-flex items-center gap-1.5 text-xs font-medium text-text-muted hover:text-text"
                >
                  <ArrowLeft className="size-3.5" />
                  All runs
                </Link>
                <h2 className="text-lg font-black">{workflow.name}</h2>
                <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-text-muted" data-run-summary>
                  <RunStatusBadge status={detail.run.status} />
                  <span className="font-mono">Run {runId.slice(-6)}</span>
                  {run?.startTimestamp != null && <span>· {formatRelativeTime(run.startTimestamp)}</span>}
                  {duration != null && <span>· {active ? "running for " : ""}{formatDuration(duration)}</span>}
                  {run?.version != null && (
                    <span className={olderVersion ? "text-amber-400" : undefined}>
                      · v{run.version}
                      {olderVersion && " (an older version)"}
                    </span>
                  )}
                </div>
                {run?.error && <p className="mt-2 text-xs text-red-400">{run.error}</p>}
                {startError && <p className="mt-2 text-xs text-red-400">{startError}</p>}
              </div>
              <div className="pointer-events-auto flex items-center gap-2">
                <Button href={`/workflows/${id}`} variant="outline">
                  Workflow
                </Button>
                <Button onClick={runAgain} loading={running} variant="secondary">
                  <Play className="size-4" />
                  Run again
                </Button>
              </div>
            </div>

            <RunStepsContext.Provider value={runSteps}>
              <WorkflowCanvas
                nodes={nodes}
                edges={edges}
                onNodesChange={onNodesChange}
                onEdgesChange={onEdgesChange}
                setNodes={() => {}}
                setEdges={() => {}}
                interactive={false}
                selectedNodeId={selectedNodeId}
                onSelectNode={setPicked}
              />
            </RunStepsContext.Provider>
          </div>

          {selectedNode && (
            <RunStepDrawer
              key={selectedNode.id}
              node={selectedNode}
              stepNumber={stepNumbers.get(selectedNode.id) ?? 1}
              step={runSteps?.get(selectedNode.id) ?? null}
              runActive={active}
              now={now}
              onClose={() => setPicked(null)}
            />
          )}
        </div>
      )}
    </>
  );
}
