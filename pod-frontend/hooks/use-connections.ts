"use client";

import { useCallback, useEffect, useState } from "react";
import { useAuth } from "@/lib/auth-context";
import { listConnections, listConnectors, listOAuthClients } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import type { AppConnection, ConnectorInfo, OAuthClient } from "@/lib/types";

// The caller's connections, their own OAuth apps, and the connectable apps, from pod-connector.
// withOAuthClients: false skips the OAuth apps for screens that don't show them.
export function useConnections({ withOAuthClients = true }: { withOAuthClients?: boolean } = {}) {
  const { token } = useAuth();
  const [connections, setConnections] = useState<AppConnection[]>([]);
  const [connectors, setConnectors] = useState<ConnectorInfo[]>([]);
  const [oauthClients, setOAuthClients] = useState<OAuthClient[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ status: number; message: string } | null>(null);

  const refresh = useCallback(async () => {
    if (!token) return;
    setError(null);
    try {
      const [list, apps, clients] = await Promise.all([
        listConnections(token),
        listConnectors(token),
        withOAuthClients ? listOAuthClients(token) : Promise.resolve([]),
      ]);
      setConnections(list);
      setConnectors(apps);
      setOAuthClients(clients);
    } catch (err) {
      setError(
        err instanceof ApiError
          ? { status: err.status, message: err.message }
          : { status: 0, message: "Couldn't reach the connections service" }
      );
    } finally {
      setLoading(false);
    }
  }, [token, withOAuthClients]);

  useEffect(() => {
    // refresh() is also exposed for re-fetching after a change; the setState
    // calls inside it are effect-driven data sync.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
  }, [refresh]);

  return { connections, connectors, oauthClients, loading, error, refresh };
}
