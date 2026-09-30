"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { getWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";
import type { Workflow } from "@/lib/types";

// One workflow, loaded once per id.
export function useWorkflow(id: string) {
  const { token } = useAuth();
  const [state, setState] = useState<{ id: string; workflow: Workflow | null; error: string | null } | null>(null);

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    getWorkflow(id, token)
      .then((workflow) => !cancelled && setState({ id, workflow, error: null }))
      .catch((err) => {
        if (!cancelled) {
          setState({ id, workflow: null, error: err instanceof ApiError ? err.message : "Failed to load workflow" });
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, token]);

  const current = state?.id === id ? state : null;
  return { workflow: current?.workflow ?? null, error: current?.error ?? null, loading: !current };
}
