"use client";

import { createContext, useContext, useEffect, useMemo } from "react";
import { useConnections } from "@/hooks/use-connections";
import type { AppConnection, ConnectorInfo } from "@/lib/types";

type StepConnections = {
  connections: AppConnection[];
  connectors: ConnectorInfo[];
  loading: boolean;
  error: { status: number; message: string } | null;
  refresh: () => Promise<void>;
};

const StepConnectionsContext = createContext<StepConnections>({
  connections: [],
  connectors: [],
  loading: false,
  error: null,
  refresh: async () => {},
});

// Loads the user's connections once for a workflow screen, so every step card and the step
// panel can show and pick accounts without their own requests. Re-fetches when the tab
// regains focus, in case a connection was added or reconnected elsewhere meanwhile.
export function StepConnectionsProvider({ children }: { children: React.ReactNode }) {
  const { connections, connectors, loading, error, refresh } = useConnections({ withOAuthClients: false });

  useEffect(() => {
    const onFocus = () => refresh();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, [refresh]);

  const value = useMemo(
    () => ({ connections, connectors, loading, error, refresh }),
    [connections, connectors, loading, error, refresh]
  );
  return <StepConnectionsContext.Provider value={value}>{children}</StepConnectionsContext.Provider>;
}

export function useStepConnections() {
  return useContext(StepConnectionsContext);
}

// For a step of the given app: whether the app takes a connection at all (connector), the
// user's connections for it, and the one the step has chosen (null if none or it's gone).
export function useStepConnection(appId: string | undefined, connectionId: string | null | undefined) {
  const { connections, connectors, loading, error, refresh } = useStepConnections();
  const connector = appId ? connectors.find((c) => c.appId === appId) ?? null : null;
  const forApp = connector ? connections.filter((c) => c.appId === appId) : [];
  const chosen = connectionId ? forApp.find((c) => c.id === connectionId) ?? null : null;
  // Chosen but not in the list: deleted since (only knowable once the list has loaded).
  const missing = !!connectionId && !chosen && !loading && !error;
  return { connector, connections: forApp, chosen, missing, loading, error, refresh };
}
