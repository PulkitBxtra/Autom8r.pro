"use client";

import { useState } from "react";
import { ChevronRight, Search, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { APP_CATALOG } from "@/lib/mock-catalog";
import { Input } from "@/components/ui/input";
import type { App, AppAction, AppTrigger } from "@/lib/types";
import type { WorkflowNode } from "@/lib/workflow-graph";

type Tab = "setup" | "configure" | "test";
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
  onSelectAppItem,
}: {
  node: WorkflowNode;
  stepNumber: number;
  readOnly?: boolean;
  onClose: () => void;
  onSelectAppItem?: (app: App, item: AppTrigger | AppAction | undefined) => void;
}) {
  const [tab, setTab] = useState<Tab>("setup");
  const [appPickerOpen, setAppPickerOpen] = useState(!node.data.app);
  const [query, setQuery] = useState("");

  const isTrigger = node.data.kind === "trigger";
  const kindLabel = isTrigger ? "Trigger event" : "Action event";
  const events = node.data.app
    ? isTrigger
      ? node.data.app.triggers
      : node.data.app.actions
    : [];

  function handlePickApp(app: App) {
    // Changing the app resets the previously chosen event -- it belonged
    // to a different app's trigger/action list.
    onSelectAppItem?.(app, undefined);
    setAppPickerOpen(false);
  }

  function handlePickEvent(itemId: string) {
    if (!node.data.app) return;
    const item = events.find((e) => e.id === itemId);
    if (item) onSelectAppItem?.(node.data.app, item);
  }

  const filteredApps = APP_CATALOG.filter((app) =>
    app.name.toLowerCase().includes(query.toLowerCase())
  );

  return (
    <div className="flex h-full w-[400px] shrink-0 flex-col border-l border-border-strong bg-surface-raised">
      <div className="flex items-center justify-between gap-3 border-b border-border px-5 py-4">
        <div className="flex min-w-0 items-center gap-3">
          <div
            className={cn(
              "flex size-9 shrink-0 items-center justify-center rounded-lg text-xs font-black",
              node.data.app ? "bg-lemon text-black" : "bg-white/5 text-text-faint"
            )}
          >
            {node.data.app?.name[0] ?? "?"}
          </div>
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
                "rounded-md px-2 py-1 text-sm font-medium transition-colors",
                tab === t.id ? "text-lemon" : "text-text-muted hover:text-text"
              )}
            >
              {t.label}
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
                  <div className="grid grid-cols-2 gap-2">
                    {filteredApps.map((app) => (
                      <button
                        key={app.id}
                        onClick={() => handlePickApp(app)}
                        className="flex items-center gap-2.5 rounded-xl border border-border-strong bg-surface-sunken px-3 py-2.5 text-left transition-colors hover:border-lemon/50 hover:bg-white/5"
                      >
                        <div className="flex size-7 shrink-0 items-center justify-center rounded-lg bg-lemon text-[11px] font-black text-black">
                          {app.name[0]}
                        </div>
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
                    <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-lemon text-xs font-black text-black">
                      {node.data.app?.name[0] ?? "?"}
                    </div>
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
            </div>
          </div>
        )}

        {tab === "configure" && (
          <p className="text-sm text-text-muted">
            Field mapping isn&apos;t available yet -- this step will run with
            its event defaults.
          </p>
        )}

        {tab === "test" && (
          <p className="text-sm text-text-muted">
            Per-step testing isn&apos;t available yet. Use{" "}
            <span className="font-semibold text-text">Run now</span> on the
            workflow page to test the whole chain.
          </p>
        )}
      </div>
    </div>
  );
}
