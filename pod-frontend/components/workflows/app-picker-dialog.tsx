"use client";

import { useState } from "react";
import { ChevronLeft, Search } from "lucide-react";
import { Dialog } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { APP_CATALOG } from "@/lib/mock-catalog";
import type { App, AppAction, AppTrigger } from "@/lib/types";

type Kind = "trigger" | "action";

export function AppPickerDialog({
  open,
  onClose,
  kind,
  onSelect,
}: {
  open: boolean;
  onClose: () => void;
  kind: Kind;
  onSelect: (app: App, item: AppTrigger | AppAction) => void;
}) {
  const [activeApp, setActiveApp] = useState<App | null>(null);
  const [query, setQuery] = useState("");

  function handleClose() {
    setActiveApp(null);
    setQuery("");
    onClose();
  }

  const items = activeApp
    ? kind === "trigger"
      ? activeApp.triggers
      : activeApp.actions
    : [];

  const filteredApps = APP_CATALOG.filter((app) =>
    app.name.toLowerCase().includes(query.toLowerCase())
  );

  return (
    <Dialog
      open={open}
      onClose={handleClose}
      title={
        activeApp
          ? activeApp.name
          : kind === "trigger"
            ? "Choose a trigger"
            : "Choose an action"
      }
    >
      {activeApp ? (
        <div>
          <button
            onClick={() => setActiveApp(null)}
            className="mb-4 flex items-center gap-1.5 text-sm font-medium text-text-muted hover:text-text"
          >
            <ChevronLeft className="size-4" />
            All apps
          </button>
          <div className="space-y-2">
            {items.length === 0 && (
              <p className="py-6 text-center text-sm text-text-muted">
                {activeApp.name} doesn&apos;t have any {kind}s yet.
              </p>
            )}
            {items.map((item) => (
              <button
                key={item.id}
                onClick={() => {
                  onSelect(activeApp, item);
                  handleClose();
                }}
                className="flex w-full items-center justify-between rounded-xl border border-border-strong bg-surface-sunken px-4 py-3 text-left text-sm font-medium transition-colors hover:border-lemon/50 hover:bg-white/5"
              >
                {item.name}
              </button>
            ))}
          </div>
        </div>
      ) : (
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
          <div className="grid max-h-96 grid-cols-2 gap-2 overflow-y-auto">
            {filteredApps.map((app) => (
              <button
                key={app.id}
                onClick={() => setActiveApp(app)}
                className="flex items-center gap-3 rounded-xl border border-border-strong bg-surface-sunken px-3.5 py-3 text-left transition-colors hover:border-lemon/50 hover:bg-white/5"
              >
                <div className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-lemon text-xs font-black text-black">
                  {app.name[0]}
                </div>
                <span className="truncate text-sm font-semibold">{app.name}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </Dialog>
  );
}
