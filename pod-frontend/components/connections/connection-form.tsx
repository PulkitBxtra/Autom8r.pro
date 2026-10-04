"use client";

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { ExternalLink, Eye, EyeOff, Search } from "lucide-react";
import { AppLogo } from "@/components/ui/app-logo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Badge } from "@/components/ui/badge";
import { OAuthSection, type OAuthTarget } from "@/components/connections/oauth-section";
import { useAuth } from "@/lib/auth-context";
import {
  createConnection,
  OAUTH_CHANNEL,
  reconnectConnection,
  startOAuth,
  type OAuthResult,
} from "@/lib/api/connections";
import { ApiError } from "@/lib/api/client";
import { cn } from "@/lib/utils";
import type { AppConnection, ConnectorInfo } from "@/lib/types";

// Step 1 of a new connection: pick the app. Each app links to its own connect page.
export function AppPicker({ connectors }: { connectors: ConnectorInfo[] }) {
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
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {filtered.map((c) => {
          // An app is connectable if it has a token form or supports OAuth (with the server's
          // app or the user's own).
          const available = !!c.tokenFields || c.oauthAvailable;
          const body = (
            <>
              <AppLogo appId={c.appId} name={c.name} className="size-10" />
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="truncate text-sm font-semibold">{c.name}</span>
                  {!available && <Badge className="shrink-0 whitespace-nowrap px-1.5 py-0 text-[9px]">Coming soon</Badge>}
                </div>
                <p className="truncate text-xs text-text-muted">{c.description}</p>
              </div>
            </>
          );
          const card = "flex items-center gap-3 rounded-xl border border-border-strong bg-surface-raised p-4 text-left transition-colors";
          return available ? (
            <Link
              key={c.appId}
              href={`/connections/new/${encodeURIComponent(c.appId)}`}
              className={cn(card, "hover:border-lemon/50 hover:bg-white/5")}
            >
              {body}
            </Link>
          ) : (
            <div key={c.appId} aria-disabled className={cn(card, "cursor-not-allowed opacity-50")}>
              {body}
            </div>
          );
        })}
        {filtered.length === 0 && (
          <p className="col-span-full py-6 text-center text-sm text-text-muted">No apps match “{query}”.</p>
        )}
      </div>
    </div>
  );
}

// Step 2: sign in with OAuth and/or enter the app's token. With `reconnect` set it replaces
// that connection's credentials in place (same id).
export function CredentialsForm({
  app,
  reconnect,
  onSaved,
}: {
  app: ConnectorInfo;
  reconnect: AppConnection | null;
  // Handed the saved connection's id.
  onSaved: (connectionId: string | null) => void;
}) {
  const { token } = useAuth();
  const [values, setValues] = useState<Record<string, string>>({});
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Shown under the OAuth button rather than at the bottom of the token form.
  const [oauthError, setOAuthError] = useState<string | null>(null);

  const fields = app.tokenFields ?? [];
  const missing = fields.some((f) => f.required && !values[f.key]?.trim());
  const oauth = useOAuthPopup({
    app,
    connectionId: reconnect?.id,
    onSuccess: onSaved,
    onError: setOAuthError,
  });

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!token || missing) return;
    setSaving(true);
    setError(null);
    try {
      const saved = reconnect
        ? await reconnectConnection(reconnect.id, values, token)
        : await createConnection(app.appId, values, token);
      onSaved(saved.id);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : `Couldn't connect ${app.name}`);
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} autoComplete="off">
      <div className="mb-6 flex items-center gap-3">
        <AppLogo appId={app.appId} name={app.name} className="size-11" />
        <div className="min-w-0">
          <p className="text-sm font-semibold">{app.name}</p>
          <p className="text-sm text-text-muted">{app.description}</p>
        </div>
      </div>

      {app.oauthAvailable && (
        <div className="mb-5">
          <OAuthSection app={app} reconnect={reconnect} waiting={oauth.waiting} connect={oauth.open} />
          {oauthError && (
            <p role="alert" className="mt-3 rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2.5 text-sm text-red-300">
              {oauthError}
            </p>
          )}
          {fields.length > 0 && (
            <div className="mt-5 flex items-center gap-3 text-xs text-text-faint">
              <span className="h-px flex-1 bg-border" />
              or use a token
              <span className="h-px flex-1 bg-border" />
            </div>
          )}
        </div>
      )}

      {fields.length === 0 ? (
        !app.oauthAvailable && <p className="text-sm text-text-muted">{app.name} can&apos;t be connected yet.</p>
      ) : (
        <div className="space-y-4">
          {fields.map((f, i) => (
            <div key={f.key}>
              <Label htmlFor={`cred-${f.key}`}>
                {f.label}
                {!f.required && <span className="font-normal text-text-muted"> (optional)</span>}
              </Label>
              <div className="relative">
                <Input
                  id={`cred-${f.key}`}
                  name={f.key}
                  autoFocus={i === 0 && !app.oauthAvailable}
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

// Runs "Connect with <provider>": opens a pop-up, sends it to the provider's sign-in page, and
// waits for /oauth-complete to report back (BroadcastChannel, or postMessage via the opener).
function useOAuthPopup({
  app,
  connectionId,
  onSuccess,
  onError,
}: {
  app: ConnectorInfo;
  connectionId?: string;
  onSuccess: (connectionId: string | null) => void;
  onError: (message: string | null) => void;
}) {
  const { token } = useAuth();
  const [waiting, setWaiting] = useState(false);
  const popupRef = useRef<Window | null>(null);
  // Latest callbacks, without re-subscribing the listeners on every render.
  const handlers = useRef({ onSuccess, onError });
  useEffect(() => {
    handlers.current = { onSuccess, onError };
  });

  useEffect(() => {
    if (!waiting) return;
    const handle = (data: unknown) => {
      const result = data as OAuthResult | null;
      if (!result || result.type !== "autom8r-oauth" || (result.appId && result.appId !== app.appId)) return;
      setWaiting(false);
      if (result.status === "success") {
        handlers.current.onSuccess(result.connectionId);
      } else {
        handlers.current.onError(result.message ?? "Sign-in didn't complete.");
      }
    };
    const channel = new BroadcastChannel(OAUTH_CHANNEL);
    channel.onmessage = (e) => handle(e.data);
    const onMessage = (e: MessageEvent) => {
      if (e.origin === window.location.origin) handle(e.data);
    };
    window.addEventListener("message", onMessage);
    // The user closed the pop-up without finishing: stop waiting.
    const closedCheck = setInterval(() => {
      if (popupRef.current?.closed) {
        setWaiting(false);
      }
    }, 700);
    return () => {
      channel.close();
      window.removeEventListener("message", onMessage);
      clearInterval(closedCheck);
    };
  }, [waiting, app.appId]);

  // prepare: returns the user's OAuth app to sign in with (null = the server's), saving a new
  // one first if needed, and the workspace for apps that need one. Runs after the pop-up is
  // open so blockers still allow it.
  async function open(prepare: () => Promise<OAuthTarget>) {
    if (!token) return;
    handlers.current.onError(null);
    // Open synchronously in the click so pop-up blockers allow it; point it at the provider once
    // we have the URL.
    const popup = window.open("", "autom8r-oauth", "width=600,height=760");
    if (!popup) {
      handlers.current.onError("Your browser blocked the sign-in pop-up. Allow pop-ups for this site and try again.");
      return;
    }
    popupRef.current = popup;
    setWaiting(true);
    try {
      const { oauthClientId, workspaceId } = await prepare();
      const { authorizeUrl } = await startOAuth(app.appId, token, { connectionId, oauthClientId, workspaceId });
      popup.location.href = authorizeUrl;
    } catch (err) {
      popup.close();
      setWaiting(false);
      handlers.current.onError(err instanceof ApiError ? err.message : "Couldn't start the sign-in");
    }
  }

  return { waiting, open };
}
