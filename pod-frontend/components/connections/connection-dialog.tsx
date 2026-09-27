"use client";

import { useState } from "react";
import { ArrowLeft, ExternalLink, Eye, EyeOff, Search } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { useAuth } from "@/lib/auth-context";
import { createConnection, reconnectConnection } from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { AppConnection, ConnectorInfo } from "@/lib/types";

// "New connection": pick an app, then fill in its credentials. With `reconnect`
// set it skips the picker and replaces that connection's credentials in place.
export function ConnectionDialog({
  open,
  connectors,
  reconnect,
  onClose,
  onSaved,
}: {
  open: boolean;
  connectors: ConnectorInfo[];
  reconnect?: AppConnection | null;
  onClose: () => void;
  onSaved: (connection: AppConnection) => void;
}) {
  const reconnectApp = reconnect ? connectors.find((c) => c.appId === reconnect.appId) ?? null : null;
  const [picked, setPicked] = useState<ConnectorInfo | null>(reconnectApp);
  const app = reconnectApp ?? picked;

  const title = reconnect
    ? `Reconnect ${reconnect.appName}${reconnect.label ? ` · ${reconnect.label}` : ""}`
    : app
      ? `Connect ${app.name}`
      : "New connection";

  return (
    <Dialog open={open} onClose={onClose} title={title} className="max-w-xl">
      {app ? (
        <CredentialsForm
          // Fresh form state per app.
          key={app.appId}
          app={app}
          reconnect={reconnect ?? null}
          onBack={reconnect ? undefined : () => setPicked(null)}
          onSaved={onSaved}
        />
      ) : (
        <AppPicker connectors={connectors} onPick={setPicked} />
      )}
    </Dialog>
  );
}

function AppPicker({
  connectors,
  onPick,
}: {
  connectors: ConnectorInfo[];
  onPick: (app: ConnectorInfo) => void;
}) {
  const [query, setQuery] = useState("");
  const filtered = connectors.filter((c) =>
    `${c.name} ${c.description}`.toLowerCase().includes(query.toLowerCase())
  );

  return (
    <div>
      <div className="relative mb-4">
        <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-text-faint" />
        <Input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search apps..."
          className="pl-9"
        />
      </div>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {filtered.map((c) => {
          // An app is connectable if it has a token form or configured OAuth.
          const available = !!c.tokenFields || c.oauthAvailable;
          return (
            <button
              key={c.appId}
              disabled={!available}
              onClick={() => onPick(c)}
              className={cn(
                "flex items-start gap-3 rounded-xl border border-border-strong bg-surface-sunken p-3 text-left transition-colors",
                available ? "hover:border-lemon/50 hover:bg-white/5" : "cursor-not-allowed opacity-50"
              )}
            >
              <AppIcon name={c.name} />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-semibold">{c.name}</span>
                  {!available && <Badge className="shrink-0 whitespace-nowrap px-1.5 py-0 text-[9px]">Coming soon</Badge>}
                </div>
                <p className="truncate text-xs text-text-muted">{c.description}</p>
              </div>
            </button>
          );
        })}
        {filtered.length === 0 && (
          <p className="col-span-full py-6 text-center text-sm text-text-muted">No apps match “{query}”.</p>
        )}
      </div>
    </div>
  );
}

function CredentialsForm({
  app,
  reconnect,
  onBack,
  onSaved,
}: {
  app: ConnectorInfo;
  reconnect: AppConnection | null;
  onBack?: () => void;
  onSaved: (connection: AppConnection) => void;
}) {
  const { token } = useAuth();
  const [values, setValues] = useState<Record<string, string>>({});
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fields = app.tokenFields ?? [];
  const missing = fields.some((f) => f.required && !values[f.key]?.trim());

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!token || missing) return;
    setSaving(true);
    setError(null);
    try {
      const saved = reconnect
        ? await reconnectConnection(reconnect.id, values, token)
        : await createConnection(app.appId, values, token);
      onSaved(saved);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Couldn't connect ${app.name}`);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} autoComplete="off">
      <div className="mb-5 flex items-center gap-3">
        {onBack && (
          <button
            type="button"
            onClick={onBack}
            aria-label="Back to apps"
            className="flex size-8 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-white/10 hover:text-text"
          >
            <ArrowLeft className="size-4" />
          </button>
        )}
        <AppIcon name={app.name} />
        <p className="text-sm text-text-muted">{app.description}</p>
      </div>

      {fields.length === 0 ? (
        <p className="text-sm text-text-muted">
          {app.name} connects by signing in with {app.oauthProvider ?? "OAuth"}, which isn&apos;t
          available yet.
        </p>
      ) : (
        <div className="space-y-4">
          {fields.map((f, i) => (
            <div key={f.key}>
              <Label htmlFor={`cred-${f.key}`}>{f.label}</Label>
              <div className="relative">
                <Input
                  id={`cred-${f.key}`}
                  name={f.key}
                  autoFocus={i === 0}
                  // new-password stops browsers from offering to save or autofill tokens.
                  type={f.secret && !revealed[f.key] ? "password" : "text"}
                  autoComplete={f.secret ? "new-password" : "off"}
                  spellCheck={false}
                  placeholder={f.placeholder ?? undefined}
                  value={values[f.key] ?? ""}
                  onChange={(e) => setValues((v) => ({ ...v, [f.key]: e.target.value }))}
                  className={cn(f.secret && "pr-10 font-mono")}
                />
                {f.secret && (
                  <button
                    type="button"
                    onClick={() => setRevealed((r) => ({ ...r, [f.key]: !r[f.key] }))}
                    aria-label={revealed[f.key] ? `Hide ${f.label}` : `Show ${f.label}`}
                    className="absolute right-2 top-1/2 flex size-7 -translate-y-1/2 items-center justify-center rounded-md text-text-faint transition-colors hover:text-text"
                  >
                    {revealed[f.key] ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                  </button>
                )}
              </div>
              {f.help && <p className="mt-1.5 text-xs text-text-muted">{f.help}</p>}
            </div>
          ))}

          {app.docsUrl && (
            <a
              href={app.docsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1.5 text-xs font-medium text-lemon hover:underline"
            >
              Where do I find this?
              <ExternalLink className="size-3" />
            </a>
          )}

          {error && (
            <p role="alert" className="rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2.5 text-sm text-red-300">
              {error}
            </p>
          )}

          <p className="text-xs text-text-faint">
            {app.name} is checked before anything is saved. Credentials are stored encrypted and
            are never shown again.
          </p>

          <div className="flex justify-end gap-2 pt-1">
            <Button type="submit" loading={saving} disabled={missing}>
              {saving ? `Checking with ${app.name}…` : reconnect ? "Reconnect" : "Connect"}
            </Button>
          </div>
        </div>
      )}
    </form>
  );
}

export function AppIcon({ name, className }: { name: string; className?: string }) {
  return (
    <div
      className={cn(
        "flex size-9 shrink-0 items-center justify-center rounded-lg bg-lemon text-sm font-black text-black",
        className
      )}
    >
      {name[0]}
    </div>
  );
}
