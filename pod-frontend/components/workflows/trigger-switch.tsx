"use client";

import { useCallback, useEffect, useState } from "react";
import { Check, Copy, Radio } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api/client";
import { getTriggerStatus, setWorkflowActive } from "@/lib/api/workflows";
import type { GraphNode, TriggerStatus, Workflow } from "@/lib/types";

// On/off for a workflow whose trigger is an app event (a new GitHub issue...). While on, the
// trigger is registered with the app and each event starts a run. Webhook-triggered workflows
// don't show this: their URL always works.
export function TriggerSwitch({
  workflow,
  trigger,
  onChange,
}: {
  workflow: Workflow;
  trigger: GraphNode;
  onChange: (workflow: Workflow) => void;
}) {
  const { token } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [status, setStatus] = useState<TriggerStatus | null>(null);
  const active = !!workflow.active;

  const loadStatus = useCallback(() => {
    if (!token || !active) return;
    getTriggerStatus(workflow.id, token).then(setStatus).catch(() => setStatus(null));
  }, [token, active, workflow.id]);

  useEffect(() => {
    loadStatus();
  }, [loadStatus]);

  async function toggle() {
    if (!token) return;
    setBusy(true);
    setError(null);
    try {
      onChange(await setWorkflowActive(workflow.id, !active, token));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Couldn't change it. Try again.");
    } finally {
      setBusy(false);
    }
  }

  const what = describe(trigger);
  return (
    <div className="mt-3 border-t border-border pt-3">
      <div className="flex items-center gap-3">
        <button
          type="button"
          role="switch"
          aria-checked={active}
          aria-label={active ? "Turn the workflow off" : "Turn the workflow on"}
          disabled={busy}
          onClick={toggle}
          className={cn(
            "relative h-6 w-11 shrink-0 rounded-full transition-colors disabled:opacity-60",
            active ? "bg-lemon" : "bg-white/15"
          )}
        >
          <span
            className={cn(
              "absolute top-0.5 size-5 rounded-full transition-all",
              active ? "left-[22px] bg-black" : "left-0.5 bg-white"
            )}
          />
        </button>
        <div className="min-w-0 text-xs">
          <p className="font-semibold">{busy ? (active ? "Turning off…" : "Turning on…") : active ? "On" : "Off"}</p>
          <p className="truncate text-text-muted">
            {active ? `Listening for ${what}` : `Turn on to run on ${what}`}
          </p>
        </div>
      </div>
      {active && status && (
        <p className={cn("mt-2 flex items-center gap-1.5 text-xs", status.status === "ACTIVE" ? "text-text-muted" : "text-red-400")}>
          <Radio className="size-3 shrink-0" />
          {status.status === "ACTIVE"
            ? status.lastEventAt
              ? `Last event ${new Date(status.lastEventAt).toLocaleString()}`
              : "No events yet"
            : status.error}
        </p>
      )}
      {active && status?.eventsUrl && <EventsUrl url={status.eventsUrl} />}
      {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
    </div>
  );
}

// A connection to the user's own Slack app: Slack sends that app's events wherever its Event
// Subscriptions point, so they need to point here.
function EventsUrl({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard blocked: the address is selectable, so it can be copied by hand.
    }
  }

  return (
    <div className="mt-2 text-xs text-text-muted">
      <p>In your Slack app → Event Subscriptions, set the Request URL to:</p>
      <div className="mt-1 flex items-center gap-1.5">
        <code className="min-w-0 flex-1 truncate rounded bg-white/5 px-1.5 py-1 font-mono select-all">{url}</code>
        <button
          type="button"
          onClick={copy}
          aria-label="Copy the Request URL"
          className="flex size-6 shrink-0 items-center justify-center rounded text-text-muted transition-colors hover:bg-white/10 hover:text-text"
        >
          {copied ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
        </button>
      </div>
    </div>
  );
}

// "“New Issue” in octo/app", "“New Message in Channel” in #support"
function describe(trigger: GraphNode) {
  const p = trigger.parameters ?? {};
  const where =
    typeof p.repository === "string" && p.repository
      ? ` in ${p.repository}`
      : typeof p.channel === "string" && p.channel
        ? ` in #${p.channel.replace(/^#/, "")}`
        : "";
  return `“${trigger.name ?? "its trigger"}”${where}`;
}
