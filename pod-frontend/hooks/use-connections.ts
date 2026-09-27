"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { listConnections, listConnectors } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import type { AppConnection, ConnectorInfo } from "@/lib/types";

// The caller's connections plus the connectable apps, from pod-connector.
export function useConnections() {
  const { token } = useAuth();
  const [connections, setConnections] = useState<AppConnection[]>([]);
  const [connectors, setConnectors] = useState<ConnectorInfo[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);

  const refresh = useCallback(async () => {
    if (!token) return;
    setError(null);
    try {
      const [list, apps] = await Promise.all([listConnections(token), listConnectors(token)]);
      setConnections(list);
      setConnectors(apps);
    } catch (err) {
      setError(
        err instanceof ApiError
          ? { status: err.status, message: err.message }
          : { status: 0, message: "Couldn't reach the connections service" }
      );
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    // refresh() is also exposed for re-fetching after a change; the setState
    // calls inside it are effect-driven data sync.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh]);

  return { connections, connectors, loading, error, refresh };
}
