"use client";

import { History } from "lucide-react";
import { EmptyState } from "@/components/ui/empty-state";
import { Spinner } from "@/components/ui/spinner";
import { RunStatusBadge } from "@/components/workflows/run-status";
import { cn, formatDuration, formatRelativeTime } from "@/lib/utils";
import { isRunActive } from "@/lib/api/runs";
import type { RunSummary } from "@/lib/types";

export function RunHistory({
  runs,
  loading,
  error,
  selectedRunId,
  currentVersion,
  now,
  onSelect,
}: {
  runs: RunSummary[];
  loading: boolean;
  error: string | null;
  selectedRunId: string | null;
  currentVersion: number | null | undefined;
  now: number;
  onSelect: (runId: string | null) => void;
}) {
  if (loading && runs.length === 0) {
    return (
      <div className="flex justify-center py-10">
        <Spinner />
      </div>
    );
  }

  if (runs.length === 0) {
    return (
      <EmptyState
        icon={History}
        title={error ? "Couldn't load runs" : "No runs yet"}
        description={error ?? "Use Run now, or call this workflow's webhook, to start one."}
      />
    );
  }

  return (
    <div className="space-y-1.5">
      {error && <p className="mb-2 text-xs text-red-400">{error} -- retrying</p>}
      {runs.map((run) => {
        const selected = run.id === selectedRunId;
        const active = isRunActive(run.status);
        const duration =
          run.startTimestamp != null
            ? (run.endTimestamp ?? (active ? now : run.startTimestamp)) - run.startTimestamp
            : null;
        const olderVersion = run.version != null && currentVersion != null && run.version !== currentVersion;

        return (
          <button
            key={run.id}
            onClick={() => onSelect(selected ? null : run.id)}
            className={cn(
              "flex w-full items-center gap-4 rounded-xl border px-4 py-3 text-left transition-colors",
              selected
                ? "border-lemon bg-lemon/5"
                : "border-border bg-surface-raised hover:border-border-strong hover:bg-white/[0.03]"
            )}
          >
            <RunStatusBadge status={run.status} className="w-28 shrink-0 justify-center" />

            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-semibold">
                {run.startTimestamp != null ? formatRelativeTime(run.startTimestamp) : "Not started"}
                {duration != null && (
                  <span className="font-normal text-text-muted">
                    {" "}· {active ? "running for " : ""}
                    {formatDuration(duration)}
                  </span>
                )}
              </p>
              {run.error && <p className="truncate text-xs text-red-400">{run.error}</p>}
            </div>

            {run.version != null && (
              <span
                className={cn("shrink-0 text-xs", olderVersion ? "text-amber-400" : "text-text-faint")}
                title={olderVersion ? "Ran on an older version of this workflow" : undefined}
              >
                v{run.version}
              </span>
            )}
            <span className="shrink-0 font-mono text-[11px] text-text-faint">{run.id.slice(-6)}</span>
          </button>
        );
      })}
    </div>
  );
}
