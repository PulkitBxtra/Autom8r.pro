"use client";

import { cn, formatDuration } from "@/lib/utils";
import { StepStatusBadge } from "@/components/workflows/run-status";
import { operatorLabel } from "@/lib/logic";
import type { StepDetail } from "@/lib/types";

// What one step did in the selected run: status, timing, error, and the exact
// input it ran with (templates resolved) and output it produced.
export function StepRunDetails({
  step,
  now,
  isTrigger,
  isCode,
}: {
  step: StepDetail;
  now: number;
  isTrigger: boolean;
  // A Code step: shows its inputs and what it printed rather than the script it was saved with.
  isCode: boolean;
}) {
  const finished = step.endedAt != null && step.status !== "RUNNING";
  const duration =
    step.startedAt != null ? (finished ? step.endedAt! : now) - step.startedAt : null;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-2">
        <StepStatusBadge step={step} />
        {step.attempt > 1 && (
          <span className="text-xs text-text-muted">{step.attempt} attempts</span>
        )}
        {duration != null && step.status !== "SKIPPED" && (
          <span className="text-xs text-text-muted">
            {finished ? "took" : "running for"} {formatDuration(duration)}
          </span>
        )}
      </div>

      {step.status === "RETRY_WAIT" && step.nextAttemptAt != null && (
        <p className="text-xs text-amber-400">
          Retrying in {formatDuration(Math.max(0, step.nextAttemptAt - now))}
        </p>
      )}
      {step.status === "SKIPPED" && (
        <p className="text-sm text-text-muted">
          Skipped: no path leading to this step was taken in this run.
        </p>
      )}
      {step.status === "CANCELLED" && (
        <p className="text-sm text-text-muted">
          Cancelled: another step failed before this one ran.
        </p>
      )}

      {step.error && (
        <JsonSection label={step.status === "FAILED" ? "Error" : "Last error"} tone="danger">
          {step.error}
        </JsonSection>
      )}
      {Array.isArray(step.output?.explain) && <Decision output={step.output!} />}
      {isTrigger && step.output != null ? (
        <TriggerData body={step.output.body} />
      ) : isCode ? (
        <CodeRunDetails step={step} />
      ) : (
        <>
          {step.input != null && <JsonSection label="Input">{json(step.input)}</JsonSection>}
          {step.output != null && <JsonSection label="Output">{json(step.output)}</JsonSection>}
        </>
      )}
    </div>
  );
}

function CodeRunDetails({ step }: { step: StepDetail }) {
  const inputs = step.input?.inputs;
  const logs = typeof step.output?.logs === "string" ? step.output.logs : "";
  const returned = step.output ? Object.fromEntries(Object.entries(step.output).filter(([k]) => k !== "logs")) : null;
  return (
    <>
      {step.input != null && (
        <JsonSection label="Inputs">{inputs && typeof inputs === "object" ? json(inputs) : "None"}</JsonSection>
      )}
      {logs.trim() !== "" && <JsonSection label="Printed output">{logs}</JsonSection>}
      {returned != null && <JsonSection label="Returned">{json(returned)}</JsonSection>}
    </>
  );
}

function JsonSection({
  label,
  tone,
  children,
}: {
  label: string;
  tone?: "danger";
  children: React.ReactNode;
}) {
  return (
    <div>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">{label}</p>
      <pre
        className={cn(
          "max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-xl border px-4 py-3 font-mono text-xs leading-relaxed",
          tone === "danger"
            ? "border-red-500/30 bg-red-500/5 text-red-300"
            : "border-border-strong bg-surface-sunken text-text"
        )}
      >
        {children}
      </pre>
    </div>
  );
}

function json(value: unknown) {
  return JSON.stringify(value, null, 2);
}

type Explained = {
  output: string;
  name: string;
  checked: boolean;
  matched?: boolean;
  match?: "all" | "any";
  conditions?: { left: unknown; op: string; right: unknown; result: boolean }[];
};

// A Logic step's decision in words: which paths matched, and each condition with the values
// it compared in this run.
function Decision({ output }: { output: Record<string, unknown> }) {
  const explain = output.explain as Explained[];
  const matched = (output.matched as string[] | undefined) ?? [];
  return (
    <div>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">Decision</p>
      <p className="mb-3 text-sm">
        {matched.length > 0 ? (
          <>
            Followed <span className="font-semibold text-lemon">{matched.join(", ")}</span>
          </>
        ) : (
          <span className="text-text-muted">Nothing matched, so the steps after it were skipped.</span>
        )}
      </p>
      <div className="space-y-2">
        {explain.map((e) => (
          <div key={e.output} className="rounded-xl border border-border-strong bg-surface-sunken px-3 py-2.5 text-xs">
            <p className="flex items-center justify-between gap-2 font-semibold">
              <span className="truncate">{e.name}</span>
              <span className={!e.checked ? "text-text-faint" : e.matched ? "text-emerald-400" : "text-text-muted"}>
                {!e.checked ? "Not checked" : e.matched ? "Matched" : "Didn't match"}
              </span>
            </p>
            {e.checked && e.conditions && (
              <ul className="mt-1.5 space-y-1">
                {e.conditions.map((c, i) => (
                  <li key={i} className="flex items-start gap-1.5">
                    <span className={c.result ? "text-emerald-400" : "text-red-400"}>{c.result ? "✓" : "✗"}</span>
                    <span className="min-w-0 break-words font-mono">
                      {show(c.left)} <span className="font-sans text-text-muted">{operatorLabel(c.op)}</span>
                      {c.right != null && <> {show(c.right)}</>}
                    </span>
                  </li>
                ))}
                {e.conditions.length > 1 && (
                  <li className="text-text-faint">{e.match === "any" ? "Any condition could match" : "All conditions had to match"}</li>
                )}
              </ul>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

function show(v: unknown) {
  if (v === null || v === undefined || v === "") return "(empty)";
  return typeof v === "string" ? `"${v}"` : JSON.stringify(v);
}

// What started the run (the webhook's body, or the app event's data), with the template each
// field is used by in later steps.
function TriggerData({ body }: { body: unknown }) {
  const fields = body && typeof body === "object" && !Array.isArray(body) ? Object.entries(body as Record<string, unknown>) : [];
  return (
    <div className="space-y-4">
      {fields.length > 0 && (
        <div>
          <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">Trigger data</p>
          <dl className="divide-y divide-border overflow-hidden rounded-xl border border-border-strong bg-surface-sunken">
            {fields.map(([key, value]) => (
              <div key={key} className="px-4 py-2.5">
                <dt className="font-mono text-[11px] text-lemon">{`{{trigger.body.${key}}}`}</dt>
                <dd className="mt-0.5 break-words text-sm text-text">{preview(value)}</dd>
              </div>
            ))}
          </dl>
          <p className="mt-2 text-xs text-text-faint">Use these in later steps with Insert data, or type the template.</p>
        </div>
      )}
      <JsonSection label={fields.length > 0 ? "Everything received" : "Trigger data"}>{json(body ?? null)}</JsonSection>
    </div>
  );
}

function preview(value: unknown) {
  if (value === null || value === undefined || value === "") return <span className="text-text-faint">(empty)</span>;
  const text = typeof value === "string" ? value : JSON.stringify(value);
  return text.length > 160 ? text.slice(0, 160) + "…" : text;
}
