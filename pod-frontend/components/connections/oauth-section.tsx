"use client";

import { useEffect, useState } from "react";
import { Check, Copy, ExternalLink, Eye, EyeOff, LogIn } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/lib/auth-context";
import { createOAuthClient, listOAuthClients } from "@/lib/api/connections";
import { cn } from "@/lib/utils";
import type { AppConnection, ConnectorInfo, OAuthClient } from "@/lib/types";

const NEW = "new";
// A Trello workspace (organization) ID.
const WORKSPACE_ID = /^[0-9a-f]{24}$/i;

// What to sign in with: the user's OAuth app (null = the server's), and the workspace for apps
// whose sign-in is for one (Trello).
export type OAuthTarget = { oauthClientId: string | null; workspaceId?: string };

// "Connect with <provider>", through the server's OAuth app or one of the user's own. With their
// own, they create an app with the provider (registering our callback URL), paste its client
// id/secret once, and pick it next time. `connect` opens the sign-in pop-up; it's handed a
// function that returns which OAuth app to use (saving a new one first if needed). Apps whose
// sign-in is for one workspace (Trello) ask for its ID first.
export function OAuthSection({
  app,
  reconnect,
  waiting,
  connect,
}: {
  app: ConnectorInfo;
  reconnect: AppConnection | null;
  waiting: boolean;
  connect: (prepare: () => Promise<OAuthTarget>) => void;
}) {
  const { token } = useAuth();
  const providerName = app.oauthProviderName ?? "OAuth";
  const [source, setSource] = useState<"platform" | "own">(
    reconnect?.oauthClientId || !app.platformOAuthAvailable ? "own" : "platform"
  );
  const [clients, setClients] = useState<OAuthClient[] | null>(null);
  const [selected, setSelected] = useState<string>(reconnect?.oauthClientId ?? NEW);
  const [form, setForm] = useState({ name: "", clientId: "", clientSecret: "" });
  const [workspace, setWorkspace] = useState("");

  useEffect(() => {
    if (!token || !app.oauthProvider) return;
    let cancelled = false;
    listOAuthClients(token, app.oauthProvider)
      .then((list) => {
        if (cancelled) return;
        setClients(list);
        // Default to their most recent app unless reconnecting with a specific one.
        setSelected((s) => (s === NEW && list.length > 0 ? list[0].id : s));
      })
      .catch(() => !cancelled && setClients([]));
    return () => {
      cancelled = true;
    };
  }, [token, app.oauthProvider]);

  const adding = source === "own" && selected === NEW;
  const workspaceId = workspace.trim();
  const workspaceMissing = !!app.oauthNeedsWorkspace && !WORKSPACE_ID.test(workspaceId);
  const incomplete = workspaceMissing || (adding && (!form.clientId.trim() || !form.clientSecret.trim()));

  async function prepare(): Promise<OAuthTarget> {
    return { oauthClientId: await oauthApp(), workspaceId: app.oauthNeedsWorkspace ? workspaceId : undefined };
  }

  async function oauthApp(): Promise<string | null> {
    if (source === "platform") return null;
    if (selected !== NEW) return selected;
    const created = await createOAuthClient(
      {
        provider: app.oauthProvider!,
        name: form.name.trim() || undefined,
        clientId: form.clientId.trim(),
        clientSecret: form.clientSecret.trim(),
      },
      token!
    );
    // Saved even if the sign-in then fails, so a retry reuses it instead of adding a duplicate.
    setClients((list) => [created, ...(list ?? [])]);
    setSelected(created.id);
    setForm({ name: "", clientId: "", clientSecret: "" });
    return created.id;
  }

  return (
    <div className="space-y-4">
      {app.platformOAuthAvailable && (
        <div role="radiogroup" aria-label="Which OAuth app to sign in with" className="grid grid-cols-2 gap-1 rounded-xl border border-border-strong bg-surface-sunken p-1">
          {(
            [
              ["platform", `Autom8r's ${providerName} app`],
              ["own", "Your own OAuth app"],
            ] as const
          ).map(([value, label]) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={source === value}
              onClick={() => setSource(value)}
              className={cn(
                "rounded-lg px-3 py-2 text-xs font-semibold transition-colors",
                source === value ? "bg-white/10 text-text" : "text-text-muted hover:text-text"
              )}
            >
              {label}
            </button>
          ))}
        </div>
      )}

      {source === "own" && (
        <div className="space-y-4 rounded-xl border border-border bg-surface-sunken/50 p-4">
          {clients && clients.length > 0 && (
            <div>
              <Label htmlFor="oauth-client">OAuth app</Label>
              <select
                id="oauth-client"
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
                className="h-11 w-full rounded-lg border border-border-strong bg-surface-sunken px-3 text-sm text-text outline-none focus:border-lemon"
              >
                {clients.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name} · {c.clientId}
                  </option>
                ))}
                <option value={NEW}>+ Add another {providerName} OAuth app</option>
              </select>
            </div>
          )}

          {adding ? (
            <NewAppForm app={app} form={form} onChange={setForm} />
          ) : (
            app.callbackUrl && <CopyField label="Callback URL" value={app.callbackUrl} />
          )}
        </div>
      )}

      {app.oauthNeedsWorkspace && (
        <div>
          <Label htmlFor="oauth-workspace">{providerName} workspace ID</Label>
          <Input
            id="oauth-workspace"
            autoComplete="off"
            spellCheck={false}
            placeholder="24 letters and digits"
            value={workspace}
            onChange={(e) => setWorkspace(e.target.value)}
            aria-invalid={workspaceId !== "" && workspaceMissing}
            className="font-mono"
          />
          <p className="mt-1.5 text-xs text-text-muted">
            {providerName} signs you in to one workspace. While signed in to {providerName}, open{" "}
            <a
              href="https://trello.com/1/members/me/organizations?fields=id,displayName"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-lemon hover:underline"
            >
              your workspaces
              <ExternalLink className="size-3" />
            </a>{" "}
            and copy the <span className="font-mono">id</span> of the one to connect.
          </p>
          {workspaceId !== "" && workspaceMissing && (
            <p role="alert" className="mt-1.5 text-xs text-red-300">
              That isn&apos;t a workspace ID: it&apos;s 24 letters and digits (0-9, a-f).
            </p>
          )}
        </div>
      )}

      <Button
        type="button"
        className="w-full"
        loading={waiting}
        disabled={incomplete}
        onClick={() => connect(prepare)}
      >
        {!waiting && <LogIn className="size-4" />}
        {waiting
          ? `Waiting for ${providerName}…`
          : adding
            ? `Save and connect with ${providerName}`
            : `Connect with ${providerName}`}
      </Button>
      {waiting && (
        <p className="text-center text-xs text-text-muted">Finish signing in in the pop-up window.</p>
      )}
    </div>
  );
}

function NewAppForm({
  app,
  form,
  onChange,
}: {
  app: ConnectorInfo;
  form: { name: string; clientId: string; clientSecret: string };
  onChange: (f: { name: string; clientId: string; clientSecret: string }) => void;
}) {
  const [revealed, setRevealed] = useState(false);
  const providerName = app.oauthProviderName ?? "the provider";

  return (
    <div className="space-y-4">
      <ol className="list-decimal space-y-1.5 pl-4 text-xs text-text-muted">
        <li>
          Create an OAuth app in{" "}
          {app.oauthSetupUrl ? (
            <a
              href={app.oauthSetupUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 font-medium text-lemon hover:underline"
            >
              {providerName}&apos;s developer settings
              <ExternalLink className="size-3" />
            </a>
          ) : (
            `${providerName}'s developer settings`
          )}
          .
        </li>
        <li>Set its callback URL to the one below.</li>
        <li>Paste the app&apos;s client ID and a client secret here.</li>
      </ol>

      {app.callbackUrl && <CopyField label="Callback URL" value={app.callbackUrl} />}

      <div>
        <Label htmlFor="oauth-client-id">Client ID</Label>
        <Input
          id="oauth-client-id"
          autoComplete="off"
          spellCheck={false}
          value={form.clientId}
          onChange={(e) => onChange({ ...form, clientId: e.target.value })}
          className="font-mono"
        />
      </div>
      <div>
        <Label htmlFor="oauth-client-secret">Client secret</Label>
        <div className="relative">
          <Input
            id="oauth-client-secret"
            type={revealed ? "text" : "password"}
            autoComplete="new-password"
            spellCheck={false}
            value={form.clientSecret}
            onChange={(e) => onChange({ ...form, clientSecret: e.target.value })}
            className="pr-10 font-mono"
          />
          <button
            type="button"
            onClick={() => setRevealed((r) => !r)}
            aria-label={revealed ? "Hide client secret" : "Show client secret"}
            className="absolute right-2 top-1/2 flex size-7 -translate-y-1/2 items-center justify-center rounded-md text-text-faint transition-colors hover:text-text"
          >
            {revealed ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
          </button>
        </div>
      </div>
      <div>
        <Label htmlFor="oauth-client-name">
          Name <span className="font-normal text-text-faint">(optional)</span>
        </Label>
        <Input
          id="oauth-client-name"
          autoComplete="off"
          placeholder={`${providerName} app`}
          value={form.name}
          onChange={(e) => onChange({ ...form, name: e.target.value })}
        />
      </div>
      <p className="text-xs text-text-faint">
        The secret is stored encrypted and never shown again. It&apos;s checked when you sign in.
      </p>
    </div>
  );
}

export function CopyField({ label, value }: { label: string; value: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked: the field is read-only and selectable, so it can be copied by hand.
    }
  }

  return (
    <div>
      <Label htmlFor={`copy-${label}`}>{label}</Label>
      <div className="flex gap-2">
        <Input
          id={`copy-${label}`}
          readOnly
          value={value}
          onFocus={(e) => e.target.select()}
          className="font-mono text-xs"
        />
        <Button type="button" variant="outline" className="h-11 shrink-0" onClick={copy} aria-label={`Copy ${label}`}>
          {copied ? <Check className="size-4" /> : <Copy className="size-4" />}
          {copied ? "Copied" : "Copy"}
        </Button>
      </div>
    </div>
  );
}
