"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { listApps } from "@/lib/api/catalog";
import { ApiError } from "@/lib/api/client";
import { buildCatalog, EMPTY_CATALOG, type Catalog } from "@/lib/catalog";

type CatalogState = Catalog & {
  loading: boolean;
  error: string | null;
  retry: () => void;
};

const CatalogContext = createContext<CatalogState | null>(null);

// Loads the app catalog once for the signed-in app; it only changes with a deploy.
export function CatalogProvider({ children }: { children: React.ReactNode }) {
  const [catalog, setCatalog] = useState<Catalog>(EMPTY_CATALOG);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setCatalog(buildCatalog(await listApps()));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't load the app list");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // Effect-driven data sync, as in useConnections.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    load();
  }, [load]);

  const value = useMemo(() => ({ ...catalog, loading, error, retry: load }), [catalog, loading, error, load]);
  return <CatalogContext.Provider value={value}>{children}</CatalogContext.Provider>;
}

export function useCatalog() {
  const ctx = useContext(CatalogContext);
  if (!ctx) throw new Error("useCatalog must be used within CatalogProvider");
  return ctx;
}
