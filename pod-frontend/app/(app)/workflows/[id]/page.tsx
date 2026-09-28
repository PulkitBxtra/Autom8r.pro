"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { ArrowLeft, History, Play, X } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { FullPageSpinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { StepPanel } from "@/components/workflows/canvas/step-panel";
import { StepConnectionsProvider } from "@/components/workflows/step-connections";
import { useCatalog } from "@/lib/catalog-context";
import { RunHistory } from "@/components/workflows/run-history";
import { RunStatusBadge, RunStepsContext } from "@/components/workflows/run-status";
import { useAuth } from "@/lib/auth-context";
import { getWorkflow, triggerWorkflow } from "@/lib/api/workflows";
import { isRunActive, isRunSettling } from "@/lib/api/runs";
import { useRun, useRuns } from "@/hooks/use-runs";
import { useNow } from "@/hooks/use-now";
import {
  buildGraphFromWorkflow,
  countActions,
  orderSteps,
  TRIGGER_NODE_ID,
} from "@/lib/workflow-graph";
import { ApiError } from "@/lib/api/client";
import type { Workflow } from "@/lib/types";

export default function WorkflowDetailPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = use(params);
  const { token } = useAuth();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [runsRefreshKey, setRunsRefreshKey] = useState(0);

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

  const { runs: listedRuns, loading: runsLoading, error: runsError } = useRuns(id, runsRefreshKey);
  const { detail: runDetail } = useRun(selectedRunId);
  // The selected run is polled more often than the list, so prefer its fresher
  // summary -- otherwise the header and its history row can briefly disagree.
  const runs = useMemo(
    () => listedRuns.map((r) => (runDetail && r.id === runDetail.run.id ? runDetail.run : r)),
    [listedRuns, runDetail]
  );
  const now = useNow(runs.some((r) => isRunActive(r.status)) || isRunSettling(runDetail));

  // A run executes the version that was current when it was triggered. If that's
  // not the version shown now, draw the run's own graph so its steps line up.
  // Compared as JSON so a poll returning the same graph doesn't rebuild the canvas.
  const runGraphJson =
    runDetail?.graph && runDetail.run.workflowVersionId !== workflow?.currentVersionId
      ? JSON.stringify(runDetail.graph)
      : null;
  const catalog = useCatalog();
  const graph = useMemo(() => {
    // Wait for the catalog so steps don't flash their stored fallback names first.
    if (!workflow || catalog.loading) return { nodes: [], edges: [] };
    return buildGraphFromWorkflow(
      runGraphJson ? { ...workflow, graph: JSON.parse(runGraphJson) } : workflow,
      catalog
    );
  }, [workflow, runGraphJson, catalog]);
  const [nodes, setNodes, onNodesChange] = useNodesState(graph.nodes);
  const [edges, setEdges, onEdgesChange] = useEdgesState(graph.edges);

  // useNodesState only reads its argument on the first render, before the
  // workflow has loaded -- push the graph in once it arrives (and when switching
  // to a run that ran a different version).
  useEffect(() => {
    setNodes(graph.nodes);
    setEdges(graph.edges);
  }, [graph, setNodes, setEdges]);

  // Selected run's steps by node id; canvas nodes read their status from this.
  const runSteps = useMemo(
    () => (runDetail ? new Map(runDetail.steps.map((s) => [s.nodeId, s])) : null),
    [runDetail]
  );

  const { orderedActionNodes } = orderSteps(nodes, edges);
  const stepNumbers = useMemo(() => {
    const map = new Map<string, number>();
    map.set(TRIGGER_NODE_ID, 1);
    orderedActionNodes.forEach((n, i) => map.set(n.id, i + 2));
    return map;
  }, [orderedActionNodes]);
  const selectedNode = nodes.find((n) => n.id === selectedNodeId);

  async function handleRun() {
    setRunning(true);
    setRunError(null);
    try {
      const executionId = await triggerWorkflow(id, { source: "manual-test" });
      // Jump straight to the new run and watch it execute.
      setSelectedRunId(executionId);
      setRunsRefreshKey((k) => k + 1);
    } catch (err) {
      setRunError(err instanceof ApiError ? err.message : "Failed to trigger workflow");
    } finally {
      setRunning(false);
    }
  }

  const viewingOlderVersion =
    runDetail?.run.version != null &&
    workflow?.version != null &&
    runDetail.run.version !== workflow.version;

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
        <div className="flex flex-1 flex-col overflow-hidden">
          <div className="flex min-h-0 flex-[2]">
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

                  {selectedRunId && (
                    <div className="mt-3 flex items-center gap-2 border-t border-border pt-3">
                      {runDetail ? (
                        <RunStatusBadge status={runDetail.run.status} />
                      ) : (
                        <span className="text-xs text-text-muted">Loading run…</span>
                      )}
                      <span className="min-w-0 truncate text-xs text-text-muted">
                        Run <span className="font-mono">{selectedRunId.slice(-6)}</span>
                        {viewingOlderVersion && (
                          <span className="text-amber-400"> · ran on v{runDetail!.run.version}</span>
                        )}
                      </span>
                      <button
                        onClick={() => setSelectedRunId(null)}
                        aria-label="Stop viewing this run"
                        className="ml-auto flex size-6 shrink-0 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-white/10 hover:text-text"
                      >
                        <X className="size-3.5" />
                      </button>
                    </div>
                  )}
                  {runDetail?.run.error && (
                    <p className="mt-2 text-xs text-red-400">{runDetail.run.error}</p>
                  )}
                  {runError && <p className="mt-2 text-xs text-red-400">{runError}</p>}
                </div>

                <Button
                  onClick={handleRun}
                  loading={running}
                  variant="secondary"
                  className="pointer-events-auto"
                >
                  <Play className="size-4" />
                  Run now
                </Button>
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
                  onSelectNode={setSelectedNodeId}
                />
              </RunStepsContext.Provider>
            </div>

            {selectedNode && (
              <StepPanel
                // Remount when switching runs so it reopens on the Test tab.
                key={`${selectedNode.id}:${selectedRunId ?? ""}`}
                node={selectedNode}
                stepNumber={stepNumbers.get(selectedNode.id) ?? 1}
                readOnly
                onClose={() => setSelectedNodeId(null)}
                run={{
                  selected: !!runDetail,
                  step: runSteps?.get(selectedNode.id) ?? null,
                  now,
                }}
              />
            )}
          </div>

          <div className="flex-1 overflow-y-auto border-t border-border p-6">
            <h3 className="mb-4 text-sm font-bold uppercase tracking-wide text-text-muted">
              Run history
            </h3>
            <RunHistory
              runs={runs}
              loading={runsLoading}
              error={runsError}
              selectedRunId={selectedRunId}
              currentVersion={workflow.version}
              now={now}
              onSelect={setSelectedRunId}
            />
          </div>
        </div>
      )}
    </StepConnectionsProvider>
  );
}
