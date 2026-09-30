"use client";

import { use, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft, History, Play } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { FullPageSpinner } from "@/components/ui/spinner";
import { EmptyState } from "@/components/ui/empty-state";
import { RunHistory } from "@/components/workflows/run-history";
import { useWorkflow } from "@/hooks/use-workflow";
import { useRuns } from "@/hooks/use-runs";
import { useNow } from "@/hooks/use-now";
import { isRunActive } from "@/lib/api/runs";
import { triggerWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";

// A workflow's runs, newest first; opening one shows what each step did.
export default function WorkflowRunsPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const { workflow, error, loading } = useWorkflow(id);
  const { runs, loading: runsLoading, error: runsError } = useRuns(id);
  const now = useNow(runs.some((r) => isRunActive(r.status)));
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

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
    <>
      <Topbar title={workflow ? `${workflow.name} · Runs` : "Runs"} />
      {loading ? (
        <FullPageSpinner />
      ) : error || !workflow ? (
        <div className="p-6">
          <EmptyState icon={History} title="Couldn't load workflow" description={error ?? "Not found"} />
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-5xl p-6">
            <Link
              href={`/workflows/${id}`}
              className="mb-3 inline-flex items-center gap-1.5 text-xs font-medium text-text-muted hover:text-text"
            >
              <ArrowLeft className="size-3.5" />
              {workflow.name}
            </Link>
            <div className="mb-5 flex items-center justify-between gap-4">
              <div>
                <h2 className="text-2xl font-black">Runs</h2>
                <p className="mt-1 text-sm text-text-muted">Open a run to see what each step received and returned.</p>
              </div>
              <Button onClick={handleRun} loading={running} variant="secondary">
                <Play className="size-4" />
                Run now
              </Button>
            </div>
            {runError && <p className="mb-3 text-sm text-red-400">{runError}</p>}
            <RunHistory
              runs={runs}
              loading={runsLoading}
              error={runsError}
              selectedRunId={null}
              currentVersion={workflow.version}
              now={now}
              onSelect={(runId) => runId && router.push(`/workflows/${id}/runs/${runId}`)}
            />
          </div>
        </div>
      )}
    </>
  );
}
