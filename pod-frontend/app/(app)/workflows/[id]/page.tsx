"use client";

import { use, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useNodesState, useEdgesState } from "@xyflow/react";
import { ArrowLeft, History, Play } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { FullPageSpinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { WorkflowCanvas } from "@/components/workflows/canvas/workflow-canvas";
import { useAuth } from "@/lib/auth-context";
import { getWorkflow, triggerWorkflow } from "@/lib/api/workflows";
import { buildGraphFromWorkflow } from "@/lib/workflow-graph";
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
  const [runResult, setRunResult] = useState<string | null>(null);

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

  const graph = useMemo(
    () => (workflow ? buildGraphFromWorkflow(workflow) : { nodes: [], edges: [] }),
    [workflow]
  );
  const [nodes, , onNodesChange] = useNodesState(graph.nodes);
  const [edges, , onEdgesChange] = useEdgesState(graph.edges);

  async function handleRun() {
    setRunning(true);
    setRunResult(null);
    try {
      const executionId = await triggerWorkflow(id, { source: "manual-test" });
      setRunResult(`Run started · execution ${executionId}`);
    } catch (err) {
      setRunResult(
        err instanceof ApiError ? err.message : "Failed to trigger workflow"
      );
    } finally {
      setRunning(false);
    }
  }

  return (
    <>
      <Topbar title={workflow?.name ?? "Workflow"} />

      {loading && <FullPageSpinner />}

      {!loading && error && (
        <div className="p-6">
          <EmptyState icon={History} title="Couldn't load workflow" description={error} />
        </div>
      )}

      {!loading && workflow && (
        <div className="flex flex-1 flex-col overflow-hidden">
          <div className="relative min-h-0 flex-[2]">
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
                  {(workflow.actions?.length ?? 0) + 1} steps
                </p>
                {runResult && (
                  <p className="mt-2 text-xs text-lemon">{runResult}</p>
                )}
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

            <WorkflowCanvas
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onEdgesChange={onEdgesChange}
              setNodes={() => {}}
              setEdges={() => {}}
              interactive={false}
            />
          </div>

          <div className="flex-1 overflow-y-auto border-t border-border p-6">
            <h3 className="mb-4 text-sm font-bold uppercase tracking-wide text-text-muted">
              Run history
            </h3>
            <EmptyState
              icon={History}
              title="Run history isn't wired up yet"
              description="pod-processor doesn't expose an endpoint to list execution runs yet -- this will populate once it does."
            />
          </div>
        </div>
      )}
    </>
  );
}
