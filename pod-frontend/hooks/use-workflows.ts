"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { listWorkflows } from "@/lib/api/workflows";
import type { Workflow } from "@/lib/types";
import { ApiError } from "@/lib/api/client";

export function useWorkflows() {
  const { token } = useAuth();
  const [workflows, setWorkflows] = useState<Workflow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError(null);
    try {
      const data = await listWorkflows(token);
      setWorkflows(data);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load workflows");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    // refresh() is also exposed for manual re-fetch (e.g. a retry button);
    // the setState calls inside it are legitimate effect-driven data sync.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh]);

  return { workflows, loading, error, refresh };
}
