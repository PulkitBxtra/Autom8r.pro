"use client";

import { useState } from "react";
import { AlertTriangle, KeyRound, Plug, Plus, RefreshCw, Trash2 } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";
import { AppLogo } from "@/components/ui/app-logo";
import { useConnections } from "@/hooks/use-connections";
import { useAuth } from "@/lib/auth-context";
import { deleteConnection, deleteOAuthClient, updateOAuthClient } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import { formatRelativeTime } from "@/lib/utils";
import type { AppConnection, OAuthClient } from "@/lib/types";

export default function ConnectionsPage() {
  const { token } = useAuth();
  const { connections, oauthClients, loading, error, refresh } = useConnections();
  const [deleting, setDeleting] = useState<AppConnection | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const [editingClient, setEditingClient] = useState<OAuthClient | null>(null);
  const clientNames = new Map(oauthClients.map((c) => [c.id, c.name]));

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
    <Button href="/connections/new">
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
                      oauthClientName={c.oauthClientId ? clientNames.get(c.oauthClientId) ?? "your OAuth app" : null}
                      onDelete={() => {
                        setDeleteError(null);
                        setDeleting(c);
                      }}
                    />
                  ))}
                </div>
              )}

              {oauthClients.length > 0 && (
                <section className="mt-10">
                  <h2 className="text-sm font-semibold">Your OAuth apps</h2>
                  <p className="mb-3 mt-1 text-xs text-text-muted">
                    Apps you registered with a provider and sign in through instead of Autom8r&apos;s.
                  </p>
                  <div className="space-y-2">
                    {oauthClients.map((c) => (
                      <OAuthClientRow key={c.id} client={c} onEdit={() => setEditingClient(c)} />
                    ))}
                  </div>
                </section>
              )}
            </>
          )}
        </div>
      </div>

      {editingClient && (
        <OAuthClientDialog
          key={editingClient.id}
          client={editingClient}
          onClose={() => setEditingClient(null)}
          onChanged={async () => {
            setEditingClient(null);
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
  oauthClientName,
  onDelete,
}: {
  connection: AppConnection;
  oauthClientName: string | null;
  onDelete: () => void;
}) {
  const needsReauth = c.status === "NEEDS_REAUTH";
  return (
    <div className="flex items-center gap-4 rounded-xl border border-border bg-surface-raised px-4 py-3">
      <AppLogo appId={c.appId} name={c.appName} />
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">
          {c.appName}
          {c.label && <span className="font-normal text-text-muted"> · {c.label}</span>}
        </p>
        {c.lastError ? (
          <p className="truncate text-xs text-red-400">{c.lastError}</p>
        ) : (
          <p className="text-xs text-text-faint">
            {c.authType === "OAUTH"
              ? `Signed in with OAuth${oauthClientName ? ` via ${oauthClientName}` : ""}`
              : "Token"}
            {c.createdAt != null && <> · added {formatRelativeTime(c.createdAt)}</>}
          </p>
        )}
      </div>
      <Badge tone={needsReauth ? "danger" : "success"} className="shrink-0">
        {needsReauth ? "Needs reconnect" : "Connected"}
      </Badge>
      <div className="flex shrink-0 items-center gap-1">
        <Button variant={needsReauth ? "outline" : "ghost"} size="sm" href={`/connections/${c.id}/reconnect`}>
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

function OAuthClientRow({ client: c, onEdit }: { client: OAuthClient; onEdit: () => void }) {
  return (
    <div className="flex items-center gap-4 rounded-xl border border-border bg-surface-raised px-4 py-3">
      <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white/5 text-text-muted">
        <KeyRound className="size-4" />
      </div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold">
          {c.name}
          <span className="font-normal text-text-muted"> · {c.providerName}</span>
        </p>
        <p className="truncate text-xs text-text-faint">
          <span className="font-mono">{c.clientId}</span> · used by {c.connectionCount} connection
          {c.connectionCount === 1 ? "" : "s"}
        </p>
      </div>
      <Button variant="ghost" size="sm" onClick={onEdit}>
        Manage
      </Button>
    </div>
  );
}

// Rename, replace the secret (after rotating it with the provider), or delete an OAuth app.
function OAuthClientDialog({
  client,
  onClose,
  onChanged,
}: {
  client: OAuthClient;
  onClose: () => void;
  onChanged: () => void;
}) {
  const { token } = useAuth();
  const [name, setName] = useState(client.name);
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState<"save" | "delete" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const inUse = client.connectionCount > 0;

  async function run(kind: "save" | "delete", action: () => Promise<unknown>) {
    setBusy(kind);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Something went wrong");
    } finally {
      setBusy(null);
    }
  }

  return (
    <Dialog open onClose={onClose} title={`${client.providerName} OAuth app`}>
      <form
        autoComplete="off"
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          if (token) run("save", () => updateOAuthClient(client.id, { name, clientSecret: secret }, token));
        }}
      >
        <div>
          <Label htmlFor="client-name">Name</Label>
          <Input id="client-name" value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <Label htmlFor="client-id">Client ID</Label>
          <Input id="client-id" readOnly value={client.clientId} className="font-mono text-text-muted" />
        </div>
        <div>
          <Label htmlFor="client-secret">New client secret</Label>
          <Input
            id="client-secret"
            type="password"
            autoComplete="new-password"
            spellCheck={false}
            placeholder="Leave empty to keep the current one"
            value={secret}
            onChange={(e) => setSecret(e.target.value)}
            className="font-mono"
          />
          <p className="mt-1.5 text-xs text-text-muted">
            After rotating the secret with {client.providerName}, paste the new one here. Connections that
            stopped working because of it need a reconnect afterwards.
          </p>
        </div>
        {error && (
          <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2.5 text-sm text-red-300">
            {error}
          </p>
        )}
        <div className="flex items-center justify-between gap-2 pt-1">
          <Button
            type="button"
            variant="ghost"
            size="sm"
            disabled={inUse || busy !== null}
            loading={busy === "delete"}
            title={inUse ? "Delete or reconnect the connections using it first" : undefined}
            onClick={() => token && run("delete", () => deleteOAuthClient(client.id, token))}
            className="text-red-400 hover:text-red-300"
          >
            {busy !== "delete" && <Trash2 className="size-3.5" />}
            Delete
          </Button>
          <div className="flex gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" loading={busy === "save"} disabled={!name.trim() || busy !== null}>
              Save
            </Button>
          </div>
        </div>
        {inUse && (
          <p className="text-xs text-text-faint">
            Used by {client.connectionCount} connection{client.connectionCount === 1 ? "" : "s"}, so it can&apos;t be
            deleted.
          </p>
        )}
      </form>
    </Dialog>
  );
}
