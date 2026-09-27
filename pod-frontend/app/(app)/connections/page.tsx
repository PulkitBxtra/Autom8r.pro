"use client";

import { useState } from "react";
import { AlertTriangle, Plug, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Dialog } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";
import { AppIcon, ConnectionDialog } from "@/components/connections/connection-dialog";
import { useConnections } from "@/hooks/use-connections";
import { useAuth } from "@/lib/auth-context";
import { deleteConnection } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import { formatRelativeTime } from "@/lib/utils";
import type { AppConnection } from "@/lib/types";

export default function ConnectionsPage() {
  const { token } = useAuth();
  const { connections, connectors, loading, error, refresh } = useConnections();
  // null = closed; "new" = new connection; otherwise reconnecting that connection.
  const [dialog, setDialog] = useState<"new" | AppConnection | null>(null);
  const [deleting, setDeleting] = useState<AppConnection | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  async function confirmDelete() {
    if (!token || !deleting) return;
    setDeleteBusy(true);
    setDeleteError(null);
    try {
      await deleteConnection(deleting.id, token);
      setDeleting(null);
      await refresh();
    } catch (err) {
      setDeleteError(err instanceof ApiError ? err.message : "Couldn't delete the connection");
    } finally {
      setDeleteBusy(false);
    }
  }

  const newButton = (
    <Button onClick={() => setDialog("new")} disabled={!!error}>
      <Plus className="size-4" />
      New connection
    </Button>
  );

  return (
    <>
      <Topbar title="Connections" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-5xl">
          {loading ? (
            <FullPageSpinner />
          ) : error ? (
            <EmptyState
              icon={AlertTriangle}
              title={error.status === 503 ? "Connections aren't set up on the server" : "Couldn't load connections"}
              description={
                error.status === 503
                  ? "pod-connector is running without CONNECTIONS_ENCRYPTION_KEY, so it can't store credentials."
                  : error.message
              }
            />
          ) : (
            <>
              <div className="mb-6 flex items-center justify-between gap-4">
                <p className="text-sm text-text-muted">
                  {connections.length === 0
                    ? "Connect the apps your workflows use."
                    : `${connections.length} connection${connections.length === 1 ? "" : "s"}`}
                </p>
                {newButton}
              </div>

              {connections.length === 0 ? (
                <EmptyState
                  icon={Plug}
                  title="No connections yet"
                  description="Connect an app once, then pick the connection in any workflow step that uses it."
                />
              ) : (
                <div className="space-y-2">
                  {connections.map((c) => (
                    <ConnectionRow
                      key={c.id}
                      connection={c}
                      onReconnect={() => setDialog(c)}
                      onDelete={() => {
                        setDeleteError(null);
                        setDeleting(c);
                      }}
                    />
                  ))}
                </div>
              )}
            </>
          )}
        </div>
      </div>

      {dialog && (
        <ConnectionDialog
          // Remount per target so the form starts clean each time.
          key={dialog === "new" ? "new" : dialog.id}
          open
          connectors={connectors}
          reconnect={dialog === "new" ? null : dialog}
          onClose={() => setDialog(null)}
          onSaved={async () => {
            setDialog(null);
            await refresh();
          }}
        />
      )}

      <Dialog open={!!deleting} onClose={() => setDeleting(null)} title="Delete connection?">
        {deleting && (
          <div className="space-y-5">
            <p className="text-sm text-text-muted">
              <span className="font-semibold text-text">
                {deleting.appName}
                {deleting.label ? ` · ${deleting.label}` : ""}
              </span>{" "}
              will be removed and its credentials deleted. Workflow steps using it will fail until they&apos;re
              switched to another connection.
            </p>
            {deleteError && <p className="text-sm text-red-400">{deleteError}</p>}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setDeleting(null)}>
                Cancel
              </Button>
              <Button variant="danger" loading={deleteBusy} onClick={confirmDelete}>
                Delete
              </Button>
            </div>
          </div>
        )}
      </Dialog>
    </>
  );
}

function ConnectionRow({
  connection: c,
  onReconnect,
  onDelete,
}: {
  connection: AppConnection;
  onReconnect: () => void;
  onDelete: () => void;
}) {
  const needsReauth = c.status === "NEEDS_REAUTH";
  return (
    <div className="flex items-center gap-4 rounded-xl border border-border bg-surface-raised px-4 py-3">
      <AppIcon name={c.appName} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">
          {c.appName}
          {c.label && <span className="font-normal text-text-muted"> · {c.label}</span>}
        </p>
        {c.lastError ? (
          <p className="truncate text-xs text-red-400">{c.lastError}</p>
        ) : (
          <p className="text-xs text-text-faint">
            {c.authType === "OAUTH" ? "Signed in with OAuth" : "Token"}
            {c.createdAt != null && <> · added {formatRelativeTime(c.createdAt)}</>}
          </p>
        )}
      </div>
      <Badge tone={needsReauth ? "danger" : "success"} className="shrink-0">
        {needsReauth ? "Needs reconnect" : "Connected"}
      </Badge>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant={needsReauth ? "outline" : "ghost"} size="sm" onClick={onReconnect}>
          <RefreshCw className="size-3.5" />
          Reconnect
        </Button>
        <Button variant="ghost" size="sm" onClick={onDelete} aria-label={`Delete ${c.appName} connection`}>
          <Trash2 className="size-3.5" />
        </Button>
      </div>
    </div>
  );
}
