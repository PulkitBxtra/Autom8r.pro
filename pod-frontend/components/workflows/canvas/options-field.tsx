"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import { Check, ChevronsUpDown, Loader2, Search } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { ApiError } from "@/lib/api/client";
import { listOptions } from "@/lib/api/connections";
import { templatePaths } from "@/lib/step-fields";
import type { FieldOption, FieldOptions } from "@/lib/types";

type Loaded = { key: string; data: FieldOptions | null; error: string | null };

// Choices from the step's account for one list ("slack.channels"...), narrowed by q. pod-connector
// keeps each list for a minute, so asking again while typing is cheap; the wait here only saves
// requests while keys are still being pressed.
function useFieldOptions(connectionId: string | null | undefined, source: string, q: string, enabled: boolean) {
  const { token } = useAuth();
  const key = `${connectionId}|${source}|${q}`;
  const [loaded, setLoaded] = useState<Loaded | null>(null);

  useEffect(() => {
    if (!enabled || !connectionId || !token) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      listOptions(connectionId, source, q, token)
        .then((data) => !cancelled && setLoaded({ key, data, error: null }))
        .catch((err) => {
          if (cancelled) return;
          setLoaded({ key, data: null, error: err instanceof ApiError ? err.message : "Couldn't load the list. Try again." });
        });
    }, q ? 250 : 0);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [enabled, connectionId, token, source, q, key]);

  const current = loaded?.key === key ? loaded : null;
  return {
    options: current?.data?.options ?? [],
    more: current?.data?.more ?? false,
    error: current?.error ?? null,
    loading: enabled && !!connectionId && !current,
  };
}

const isTemplate = (value: string) => templatePaths(value).length > 0;

// The account's choice a saved value is, if it's one ("C0SUPPORT" -> #support). Undefined for
// typed values the list doesn't hold, data from earlier steps, or until the list has loaded.
export function useOptionFor(connectionId: string | null | undefined, source: string, value: string) {
  const plain = value.trim() !== "" && !isTemplate(value);
  const { options } = useFieldOptions(connectionId, source, "", plain);
  return plain ? options.find((o) => o.value === value.trim()) : undefined;
}

// What a saved value is, in words ("#support · 12 members"), when it's one of the account's
// choices.
export function OptionLabel({
  connectionId,
  source,
  value,
}: {
  connectionId: string | null | undefined;
  source: string;
  value: string;
}) {
  const match = useOptionFor(connectionId, source, value);
  if (!match) return null;
  return (
    <p className="mt-1.5 flex items-center gap-1.5 text-xs text-text-muted" data-option-label>
      <Check className="size-3 shrink-0 text-lemon" />
      <span className="truncate">
        <span className="font-medium text-text">{match.label}</span>
        {match.hint && <span className="text-text-faint"> · {match.hint}</span>}
      </span>
    </p>
  );
}

// A text setting whose value can be picked from the step's account: the text box (typing an id or
// inserting data still works) plus a button that lists the choices.
export function OptionsField({
  connectionId,
  source,
  value,
  onChange,
  children,
}: {
  connectionId: string | null | undefined;
  source: string;
  value: string;
  onChange: (value: string) => void;
  // The text box.
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <div className="flex gap-2">
        <div className="min-w-0 flex-1">{children}</div>
        <button
          type="button"
          onClick={() => setOpen((o) => !o)}
          disabled={!connectionId}
          aria-label="Choose from your account"
          title={connectionId ? "Choose from your account" : "Choose an account on the Setup tab first"}
          className={cn(
            "flex size-11 shrink-0 items-center justify-center rounded-lg border border-border-strong bg-surface-sunken text-text-muted transition-colors hover:border-lemon hover:text-lemon disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:border-border-strong disabled:hover:text-text-muted",
            open && "border-lemon text-lemon"
          )}
        >
          <ChevronsUpDown className="size-4" />
        </button>
      </div>
      {!connectionId ? (
        <p className="mt-1.5 text-xs text-text-faint">Choose an account on the Setup tab to pick from a list.</p>
      ) : (
        <OptionLabel connectionId={connectionId} source={source} value={value} />
      )}
      {open && connectionId && (
        <OptionsMenu
          connectionId={connectionId}
          source={source}
          selected={value.trim()}
          onPick={(o) => {
            onChange(o.value);
            setOpen(false);
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  );
}

function OptionsMenu({
  connectionId,
  source,
  selected,
  onPick,
  onClose,
}: {
  connectionId: string;
  source: string;
  selected: string;
  onPick: (option: FieldOption) => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [q, setQ] = useState("");
  const { options, more, error, loading } = useFieldOptions(connectionId, source, q.trim(), true);

  useEffect(() => {
    function onDown(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") onClose();
    }
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [onClose]);

  return (
    <div
      ref={ref}
      role="dialog"
      aria-label="Choose from your account"
      className="absolute right-0 top-12 z-20 w-full overflow-hidden rounded-xl border border-border-strong bg-surface-raised shadow-2xl"
    >
      <div className="relative border-b border-border p-2">
        <Search className="pointer-events-none absolute left-4.5 top-1/2 size-3.5 -translate-y-1/2 text-text-faint" />
        <input
          autoFocus
          value={q}
          onChange={(e) => setQ(e.target.value)}
          placeholder="Search…"
          aria-label="Search the list"
          spellCheck={false}
          className="h-9 w-full rounded-lg border border-border-strong bg-surface-sunken pl-8 pr-3 text-sm text-text outline-none placeholder:text-text-faint focus:border-lemon"
        />
      </div>
      <div className="max-h-64 overflow-y-auto p-1.5" role="listbox" aria-label="Choices">
        {loading ? (
          <p className="flex items-center gap-2 px-2.5 py-2 text-xs text-text-muted">
            <Loader2 className="size-3.5 animate-spin" /> Loading…
          </p>
        ) : error ? (
          <p className="px-2.5 py-2 text-xs text-red-400" role="alert">
            {error}
          </p>
        ) : options.length === 0 ? (
          <p className="px-2.5 py-2 text-xs text-text-muted">{q ? "Nothing matches." : "Nothing to choose from."}</p>
        ) : (
          options.map((o) => (
            <button
              key={o.value}
              type="button"
              role="option"
              aria-selected={o.value === selected}
              onClick={() => onPick(o)}
              className="flex w-full items-center gap-2 rounded-lg px-2.5 py-1.5 text-left transition-colors hover:bg-white/5"
            >
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm">{o.label}</span>
                {o.hint && <span className="block truncate text-[11px] text-text-faint">{o.hint}</span>}
              </span>
              {o.value === selected && <Check className="size-3.5 shrink-0 text-lemon" />}
            </button>
          ))
        )}
      </div>
      {more && !loading && !error && (
        <p className="border-t border-border px-4 py-2 text-[11px] text-text-faint">Showing the first 100. Type to narrow.</p>
      )}
    </div>
  );
}
