"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { getRun, isRunActive, isRunSettling, listRuns } from "@/lib/api/runs";
import { ApiError } from "@/lib/api/client";
import type { RunDetail, RunSummary } from "@/lib/types";

const ACTIVE_RUN_POLL_MS = 1000;
const ACTIVE_LIST_POLL_MS = 1000;
const ERROR_RETRY_MS = 5000;

// A workflow's recent runs. Re-polls while any listed run is still PENDING or
// RUNNING so statuses update live, and stops once everything has finished.
// Bump refreshKey to fetch again (e.g. right after triggering a run).
export function useRuns(workflowId: string, refreshKey = 0) {
  const { token } = useAuth();
  const [state, setState] = useState<{
    key: string;
    runs: RunSummary[];
    error: string | null;
  } | null>(null);
  const key = `${workflowId}:${refreshKey}`;

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function tick() {
      try {
        const runs = await listRuns(workflowId, token!);
        if (cancelled) return;
        setState({ key, runs, error: null });
        if (runs.some((r) => isRunActive(r.status))) {
          timer = setTimeout(tick, ACTIVE_LIST_POLL_MS);
        }
      } catch (err) {
        if (cancelled) return;
        setState((prev) => ({
          key,
          runs: prev?.runs ?? [],
          error: err instanceof ApiError ? err.message : "Failed to load runs",
        }));
        timer = setTimeout(tick, ERROR_RETRY_MS);
      }
    }
    tick();

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [workflowId, token, key]);

  // Until this key's first response lands, show the previous list rather than
  // flashing empty (keeps the history steady across a refresh).
  return {
    runs: state?.runs ?? [],
    loading: state === null,
    error: state?.key === key ? state.error : null,
  };
}

// One run with its steps, polled every second until it and all its steps have settled.
export function useRun(runId: string | null) {
  const { token } = useAuth();
  const [state, setState] = useState<{
    runId: string;
    detail: RunDetail | null;
    error: string | null;
  } | null>(null);

  useEffect(() => {
    if (!token || !runId) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    async function tick() {
      try {
        const detail = await getRun(runId!, token!);
        if (cancelled) return;
        setState({ runId: runId!, detail, error: null });
        if (isRunSettling(detail)) {
          timer = setTimeout(tick, ACTIVE_RUN_POLL_MS);
        }
      } catch (err) {
        if (cancelled) return;
        setState((prev) => ({
          runId: runId!,
          detail: prev?.runId === runId ? prev.detail : null,
          error: err instanceof ApiError ? err.message : "Failed to load run",
        }));
        timer = setTimeout(tick, ERROR_RETRY_MS);
      }
    }
    tick();

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [runId, token]);

  // Ignore state left over from a previously selected run.
  const current = state && state.runId === runId ? state : null;
  return {
    detail: current?.detail ?? null,
    loading: !!runId && !current,
    error: current?.error ?? null,
  };
}
