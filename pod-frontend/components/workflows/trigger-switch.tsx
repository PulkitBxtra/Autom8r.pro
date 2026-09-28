"use client";

import { useCallback, useEffect, useState } from "react";
import { Radio } from "lucide-react";
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
      {error && <p className="mt-2 text-xs text-red-400">{error}</p>}
    </div>
  );
}

// "“New Issue” in octo/app"
function describe(trigger: GraphNode) {
  const repo = typeof trigger.parameters?.repository === "string" ? ` in ${trigger.parameters.repository}` : "";
  return `“${trigger.name ?? "its trigger"}”${repo}`;
}
