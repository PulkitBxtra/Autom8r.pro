"use client";

import { useState } from "react";
import Link from "next/link";
import { AlertTriangle, ChevronRight, Plus, Search, X } from "lucide-react";
import { cn, formatDuration } from "@/lib/utils";
import { AppLogo } from "@/components/ui/app-logo";
import { useCatalog } from "@/lib/catalog-context";
import { Input } from "@/components/ui/input";
import { StepStatusBadge } from "@/components/workflows/run-status";
import { useStepConnection, useStepConnections } from "@/components/workflows/step-connections";
import { CredentialsForm } from "@/components/connections/connection-form";
import { StepConfigForm } from "@/components/workflows/canvas/step-config";
import { defaultParameters, missingRequired, type DataSource } from "@/lib/step-fields";
import { operatorLabel } from "@/lib/logic";
import type { App, AppConnection, StepDetail } from "@/lib/types";
import type { GraphNodeData, WorkflowNode } from "@/lib/workflow-graph";

export type StepTab = "setup" | "configure" | "test";
type Tab = StepTab;
const TABS: { id: Tab; label: string }[] = [
  { id: "setup", label: "Setup" },
  { id: "configure", label: "Configure" },
  { id: "test", label: "Test" },
];

export function StepPanel({
  node,
  stepNumber,
  readOnly = false,
  onClose,
  onChange,
  run,
  sources = [],
  initialTab,
  showMissing = false,
}: {
  node: WorkflowNode;
  stepNumber: number;
  readOnly?: boolean;
  // Data this step can use: the trigger and the steps that always run before it.
  sources?: DataSource[];
  initialTab?: StepTab;
  // Mark empty required settings (after a save attempt).
  showMissing?: boolean;
  onClose: () => void;
  // Applies an edit to this step's data (app, event, connection).
  onChange?: (patch: Partial<GraphNodeData>) => void;
  // The run being viewed, if any: whether one is selected, and this step's part in it.
  run?: { selected: boolean; step: StepDetail | null; now: number };
}) {
  // Viewing a run means the user wants to see what happened, so open on Test.
  const [tab, setTab] = useState<Tab>(initialTab ?? (run?.selected ? "test" : "setup"));
  const [appPickerOpen, setAppPickerOpen] = useState(!node.data.app);
  const [query, setQuery] = useState("");
  const { connections } = useStepConnections();
  const catalog = useCatalog();

  const isTrigger = node.data.kind === "trigger";
  const missingCount = missingRequired(node.data.item?.fields, node.data.parameters).length;
  const kindLabel = isTrigger ? "Trigger event" : "Action event";
  const events = node.data.app
    ? isTrigger
      ? node.data.app.triggers
      : node.data.app.actions
    : [];

  function handlePickApp(app: App) {
    // Changing the app resets the previously chosen event and account -- they
    // belonged to the other app. With exactly one account for the new app, use it.
    const accounts = connections.filter((c) => c.appId === app.id);
    onChange?.({
      app,
      item: undefined,
      parameters: {},
      connectionId: accounts.length === 1 ? accounts[0].id : null,
    });
    setAppPickerOpen(false);
  }

  function handlePickEvent(itemId: string) {
    if (!node.data.app) return;
    const item = events.find((e) => e.id === itemId);
    // A different event takes different settings: start from its defaults.
    if (item && item.id !== node.data.item?.id) onChange?.({ item, parameters: defaultParameters(item.fields) });
  }

  // Only apps that have something for this kind of step (e.g. Webhook only triggers).
  const filteredApps = catalog.apps.filter(
    (app) =>
      (isTrigger ? app.triggers.length > 0 : app.actions.length > 0) &&
      app.name.toLowerCase().includes(query.toLowerCase())
  );

  return (
    <div className="flex h-full w-[400px] shrink-0 flex-col border-l border-border-strong bg-surface-raised">
      <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
        <div className="flex min-w-0 items-center gap-3">
          {node.data.app ? (
            <AppLogo appId={node.data.app.id} name={node.data.app.name} />
          ) : (
            <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-white/5 text-xs font-black text-text-faint">
              ?
            </div>
          )}
          <p className="truncate text-sm font-bold">
            {stepNumber}.{" "}
            {node.data.item?.name || (isTrigger ? "Choose a trigger" : "Choose an action")}
          </p>
        </div>
        <button
          onClick={onClose}
          aria-label="Close panel"
          className="flex size-7 shrink-0 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-white/10 hover:text-text"
        >
          <X className="size-4" />
        </button>
      </div>

      <div className="flex items-center gap-1.5 border-b border-border px-5 py-2.5">
        {TABS.map((t, i) => (
          <div key={t.id} className="flex items-center gap-1.5">
            {i > 0 && <ChevronRight className="size-3.5 text-text-faint" />}
            <button
              onClick={() => setTab(t.id)}
              className={cn(
                "relative rounded-md px-2 py-1 text-sm font-medium transition-colors",
                tab === t.id ? "text-lemon" : "text-text-muted hover:text-text"
              )}
            >
              {t.label}
              {t.id === "configure" && !readOnly && missingCount > 0 && (
                <span
                  aria-label={`${missingCount} required ${missingCount === 1 ? "setting" : "settings"} missing`}
                  className="absolute -right-0.5 top-0.5 size-1.5 rounded-full bg-amber-400"
                />
              )}
            </button>
          </div>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-5">
        {tab === "setup" && (
          <div className="space-y-6">
            <div>
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">
                App
              </p>

              {appPickerOpen && !readOnly ? (
                <div>
                  <div className="relative mb-3">
                    <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-text-faint" />
                    <Input
                      autoFocus
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                      placeholder="Search apps..."
                      className="pl-9"
                    />
                  </div>
                  {catalog.error && (
                    <p className="mb-3 text-sm text-red-400">
                      {catalog.error}{" "}
                      <button onClick={catalog.retry} className="font-semibold text-lemon hover:underline">
                        Retry
                      </button>
                    </p>
                  )}
                  {catalog.loading && (
                    <div className="grid grid-cols-2 gap-2">
                      {[0, 1, 2, 3].map((i) => (
                        <div key={i} className="h-12 animate-pulse rounded-xl bg-white/5" />
                      ))}
                    </div>
                  )}
                  <div className="grid grid-cols-2 gap-2">
                    {filteredApps.map((app) => (
                      <button
                        key={app.id}
                        onClick={() => handlePickApp(app)}
                        className="flex items-center gap-2.5 rounded-xl border border-border-strong bg-surface-sunken px-3 py-2.5 text-left transition-colors hover:border-lemon/50 hover:bg-white/5"
                      >
                        <AppLogo appId={app.id} name={app.name} className="size-7 rounded-md" />
                        <span className="truncate text-sm font-semibold">
                          {app.name}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              ) : (
                <div className="flex items-center justify-between gap-3 rounded-xl border border-border-strong bg-surface-sunken px-4 py-3">
                  <div className="flex min-w-0 items-center gap-3">
                    <AppLogo appId={node.data.app?.id} name={node.data.app?.name ?? "?"} className="size-8" />
                    <span className="truncate text-sm font-semibold">
                      {node.data.app?.name}
                    </span>
                  </div>
                  {!readOnly && (
                    <button
                      onClick={() => setAppPickerOpen(true)}
                      className="shrink-0 rounded-full bg-lemon px-3 py-1.5 text-xs font-bold text-black transition-colors hover:bg-lemon-dim"
                    >
                      Change
                    </button>
                  )}
                </div>
              )}
            </div>

            <div>
              <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">
                {kindLabel}
              </p>

              {readOnly ? (
                <div className="rounded-xl border border-border-strong bg-surface-sunken px-4 py-3 text-sm font-medium text-text">
                  {node.data.item?.name || "Not set"}
                </div>
              ) : (
                <select
                  value={node.data.item?.id ?? ""}
                  onChange={(e) => handlePickEvent(e.target.value)}
                  disabled={!node.data.app}
                  className="h-11 w-full rounded-lg border border-border-strong bg-surface-sunken px-3.5 text-sm text-text outline-none transition-colors focus:border-lemon disabled:opacity-50"
                >
                  <option value="" disabled>
                    {node.data.app ? `Select an event` : "Choose an app first"}
                  </option>
                  {events.map((event) => (
                    <option key={event.id} value={event.id}>
                      {event.name}
                    </option>
                  ))}
                </select>
              )}
              {node.data.item?.description && (
                <p className="mt-2 text-xs text-text-muted">{node.data.item.description}</p>
              )}
            </div>

            {node.data.app && (
              <AccountSection
                app={node.data.app}
                connectionId={node.data.connectionId}
                readOnly={readOnly}
                onSelect={(connectionId) => onChange?.({ connectionId })}
              />
            )}
          </div>
        )}

        {tab === "configure" &&
          (node.data.item ? (
            <StepConfigForm
              // Remount per event, so inputs that keep their own text start fresh.
              key={node.data.item.id}
              fields={node.data.item.fields}
              values={node.data.parameters ?? {}}
              sources={sources}
              readOnly={readOnly}
              showMissing={showMissing}
              onChange={(parameters) => onChange?.({ parameters })}
            />
          ) : (
            <p className="text-sm text-text-muted">
              Choose {isTrigger ? "a trigger" : "an action"} on the Setup tab first.
            </p>
          ))}

        {tab === "test" &&
          (run?.selected ? (
            run.step ? (
              <StepRunDetails step={run.step} now={run.now} />
            ) : (
              <p className="text-sm text-text-muted">
                This step wasn&apos;t part of the selected run -- the workflow
                has changed since that run.
              </p>
            )
          ) : (
            <p className="text-sm text-text-muted">
              Pick a run in <span className="font-semibold text-text">Run history</span>{" "}
              to see what this step received and returned, or use{" "}
              <span className="font-semibold text-text">Run now</span> to start one.
            </p>
          ))}
      </div>
    </div>
  );
}

// Which of the user's accounts (connections) this step acts through. Hidden for apps that
// don't take one. Picking only ever references a saved connection; new ones are added here
// through the same form as the Connections page, then selected.
function AccountSection({
  app,
  connectionId,
  readOnly,
  onSelect,
}: {
  app: App;
  connectionId: string | null | undefined;
  readOnly: boolean;
  onSelect: (connectionId: string | null) => void;
}) {
  const { connector, connections, chosen, missing, loading, error, refresh } = useStepConnection(
    app.id,
    connectionId
  );
  const [adding, setAdding] = useState(false);
  const optional = app.connectionOptional;

  if (!connector && !(readOnly && connectionId)) return null;

  const heading = (
    <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">
      {app.name} account
      {optional && <span className="font-normal normal-case tracking-normal text-text-faint"> (optional)</span>}
    </p>
  );

  if (loading) {
    return (
      <div>
        {heading}
        <div className="h-11 animate-pulse rounded-lg bg-white/5" />
      </div>
    );
  }
  if (error) {
    return (
      <div>
        {heading}
        <p className="text-sm text-red-400">
          Couldn&apos;t load your accounts: {error.message}{" "}
          <button onClick={() => refresh()} className="font-semibold text-lemon hover:underline">
            Retry
          </button>
        </p>
      </div>
    );
  }

  if (readOnly) {
    return (
      <div>
        {heading}
        <div className="rounded-xl border border-border-strong bg-surface-sunken px-4 py-3 text-sm font-medium text-text">
          {chosen ? accountLabel(chosen) : missing ? "Removed since this was saved" : optional ? "No account" : "None chosen"}
        </div>
        {chosen && <AccountWarning connection={chosen} />}
      </div>
    );
  }

  // Optional accounts don't open the form until asked.
  const showForm = adding || (connections.length === 0 && !optional);

  return (
    <div>
      {heading}
      {connections.length > 0 && (
        <select
          aria-label={`${app.name} account`}
          value={chosen ? chosen.id : ""}
          onChange={(e) => onSelect(e.target.value || null)}
          className="h-11 w-full rounded-lg border border-border-strong bg-surface-sunken px-3.5 text-sm text-text outline-none transition-colors focus:border-lemon"
        >
          <option value="" disabled={!optional}>
            {missing ? "The chosen account was removed; pick another" : optional ? "No account" : "Select an account"}
          </option>
          {connections.map((c) => (
            <option key={c.id} value={c.id}>
              {accountLabel(c)}
              {c.status === "NEEDS_REAUTH" ? " (needs reconnecting)" : ""}
            </option>
          ))}
        </select>
      )}
      {chosen && <AccountWarning connection={chosen} />}

      {showForm ? (
        <div className="mt-3 rounded-xl border border-border-strong bg-surface-sunken/50 p-4">
          <div className="mb-3 flex items-center justify-between gap-2">
            <p className="text-sm font-semibold">
              {connections.length === 0 ? `Connect your ${app.name} account` : `Connect another ${app.name} account`}
            </p>
            {(connections.length > 0 || optional) && (
              <button
                onClick={() => setAdding(false)}
                className="text-xs font-medium text-text-muted hover:text-text"
              >
                Cancel
              </button>
            )}
          </div>
          <CredentialsForm
            app={connector!}
            reconnect={null}
            onSaved={async (id) => {
              await refresh();
              if (id) onSelect(id);
              setAdding(false);
            }}
          />
        </div>
      ) : (
        <button
          onClick={() => setAdding(true)}
          className="mt-2 inline-flex items-center gap-1.5 text-xs font-semibold text-lemon hover:underline"
        >
          <Plus className="size-3.5" />
          {connections.length === 0 ? "Add an account" : `Connect another ${app.name} account`}
        </button>
      )}
    </div>
  );
}

function AccountWarning({ connection }: { connection: AppConnection }) {
  if (connection.status !== "NEEDS_REAUTH") return null;
  return (
    <p className="mt-2 flex items-start gap-1.5 text-xs text-amber-400">
      <AlertTriangle className="mt-0.5 size-3.5 shrink-0" />
      <span>
        This account needs reconnecting before the step can use it.{" "}
        <Link
          href={`/connections/${connection.id}/reconnect`}
          target="_blank"
          className="font-semibold underline"
        >
          Reconnect
        </Link>
      </span>
    </p>
  );
}

function accountLabel(c: AppConnection) {
  return c.label || `${c.appName} (${c.authType === "OAUTH" ? "signed in" : "token"})`;
}

// What one step did in the selected run: status, timing, error, and the exact
// input it ran with (templates resolved) and output it produced.
function StepRunDetails({ step, now }: { step: StepDetail; now: number }) {
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
      {step.input != null && <JsonSection label="Input">{json(step.input)}</JsonSection>}
      {step.output != null && <JsonSection label="Output">{json(step.output)}</JsonSection>}
    </div>
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
