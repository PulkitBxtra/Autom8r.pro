"use client";

import { useEffect, useRef, useState } from "react";
import { Braces, ChevronLeft, Plus, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { AppLogo } from "@/components/ui/app-logo";
import { describePath, isEmptyValue, templatePaths, type DataSource } from "@/lib/step-fields";
import type { CatalogField } from "@/lib/types";

// The Configure tab: one input per catalog field, saved into the step's parameters by key.
// Text-like inputs can insert data from the trigger or earlier steps as {{...}} templates.
export function StepConfigForm({
  fields,
  values,
  sources,
  readOnly,
  showMissing,
  onChange,
}: {
  fields: CatalogField[];
  values: Record<string, unknown>;
  sources: DataSource[];
  readOnly: boolean;
  // Mark empty required fields (after a save attempt found them).
  showMissing: boolean;
  onChange: (values: Record<string, unknown>) => void;
}) {
  if (fields.length === 0) {
    const saved = Object.keys(values).length > 0;
    return (
      <div className="space-y-3">
        <p className="text-sm text-text-muted">This event has no settings.</p>
        {saved && (
          <pre className="max-h-60 overflow-auto rounded-xl border border-border-strong bg-surface-sunken px-4 py-3 font-mono text-xs">
            {JSON.stringify(values, null, 2)}
          </pre>
        )}
      </div>
    );
  }

  function set(key: string, value: unknown) {
    const next = { ...values };
    if (value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  }

  return (
    <div className="space-y-5">
      {fields.map((field) => {
        const value = values[field.key];
        const missing = showMissing && field.required && field.type !== "boolean" && isEmptyValue(value);
        return (
          <div key={field.key} data-field={field.key}>
            <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">
              <label htmlFor={`field-${field.key}`}>{field.label}</label>
              {field.required && <span className="ml-0.5 text-red-400">*</span>}
            </p>
            {readOnly ? (
              <ReadOnlyValue field={field} value={value} />
            ) : (
              <FieldInput field={field} value={value} sources={sources} invalid={missing} onChange={(v) => set(field.key, v)} />
            )}
            {missing && <p className="mt-1.5 text-xs text-red-400">Required</p>}
            {field.help && <p className="mt-1.5 text-xs text-text-faint">{field.help}</p>}
            <UsedData value={value} sources={sources} />
          </div>
        );
      })}
    </div>
  );
}

function FieldInput({
  field,
  value,
  sources,
  invalid,
  onChange,
}: {
  field: CatalogField;
  value: unknown;
  sources: DataSource[];
  invalid: boolean;
  onChange: (value: unknown) => void;
}) {
  const id = `field-${field.key}`;
  switch (field.type) {
    case "boolean":
      return (
        <button
          id={id}
          type="button"
          role="switch"
          aria-checked={value === true}
          onClick={() => onChange(!(value === true))}
          className={cn(
            "relative h-6 w-11 rounded-full transition-colors",
            value === true ? "bg-lemon" : "bg-white/15"
          )}
        >
          <span
            className={cn(
              "absolute top-0.5 size-5 rounded-full bg-white transition-all",
              value === true ? "left-[22px] bg-black" : "left-0.5"
            )}
          />
        </button>
      );
    case "select":
      return (
        <select
          id={id}
          value={typeof value === "string" ? value : ""}
          onChange={(e) => onChange(e.target.value || undefined)}
          className={cn(
            "h-11 w-full rounded-lg border bg-surface-sunken px-3.5 text-sm text-text outline-none transition-colors focus:border-lemon",
            invalid ? "border-red-500/60" : "border-border-strong"
          )}
        >
          <option value="" disabled={field.required}>
            {field.required ? "Select…" : "None"}
          </option>
          {field.options?.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      );
    case "keyvalue":
      return <KeyValueInput value={value} sources={sources} invalid={invalid} onChange={onChange} />;
    case "json":
      return <JsonInput id={id} field={field} value={value} sources={sources} invalid={invalid} onChange={onChange} />;
    case "number":
      return (
        <TemplateInput
          id={id}
          value={value == null ? "" : String(value)}
          placeholder={field.placeholder ?? undefined}
          sources={sources}
          invalid={invalid}
          inputMode="decimal"
          // A plain number is saved as a number; anything else (a {{template}}) as text.
          onChange={(text) => onChange(text.trim() === "" ? undefined : /^-?\d+(\.\d+)?$/.test(text.trim()) ? Number(text) : text)}
        />
      );
    default:
      return (
        <TemplateInput
          id={id}
          multiline={field.type === "textarea"}
          value={typeof value === "string" ? value : value == null ? "" : String(value)}
          placeholder={field.placeholder ?? undefined}
          sources={sources}
          invalid={invalid}
          onChange={(text) => onChange(text === "" ? undefined : text)}
        />
      );
  }
}

// Text box with an "insert data" button that drops a {{...}} template at the cursor.
function TemplateInput({
  id,
  value,
  onChange,
  sources,
  multiline = false,
  mono = false,
  rows,
  placeholder,
  invalid,
  inputMode,
  ariaLabel,
}: {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  sources: DataSource[];
  multiline?: boolean;
  mono?: boolean;
  rows?: number;
  placeholder?: string;
  invalid?: boolean;
  inputMode?: "decimal";
  ariaLabel?: string;
}) {
  const ref = useRef<HTMLInputElement & HTMLTextAreaElement>(null);
  const caret = useRef<[number, number] | null>(null);
  const [picking, setPicking] = useState(false);

  function remember() {
    const el = ref.current;
    if (el) caret.current = [el.selectionStart ?? el.value.length, el.selectionEnd ?? el.value.length];
  }

  function insert(path: string) {
    const token = `{{${path}}}`;
    const [start, end] = caret.current ?? [value.length, value.length];
    onChange(value.slice(0, start) + token + value.slice(end));
    setPicking(false);
    const at = start + token.length;
    caret.current = [at, at];
    requestAnimationFrame(() => {
      ref.current?.focus();
      ref.current?.setSelectionRange(at, at);
    });
  }

  const className = cn(
    "w-full rounded-lg border bg-surface-sunken px-3.5 text-sm text-text placeholder:text-text-faint outline-none transition-colors focus:border-lemon",
    sources.length > 0 && "pr-10",
    mono && "font-mono text-xs",
    invalid ? "border-red-500/60" : "border-border-strong"
  );
  const common = {
    id,
    ref,
    value,
    placeholder,
    "aria-label": ariaLabel,
    spellCheck: false,
    onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => onChange(e.target.value),
    onSelect: remember,
    onKeyUp: remember,
    onBlur: remember,
  };

  return (
    <div className="relative">
      {multiline ? (
        <textarea {...common} rows={rows ?? 4} className={cn(className, "block resize-y py-3")} />
      ) : (
        <input {...common} inputMode={inputMode} className={cn(className, "h-11")} />
      )}
      {sources.length > 0 && (
        <button
          type="button"
          // Keep the caret where it is: don't take focus from the text box on mouse down.
          onMouseDown={(e) => e.preventDefault()}
          onClick={() => setPicking((p) => !p)}
          aria-label="Insert data from an earlier step"
          title="Insert data from an earlier step"
          className={cn(
            "absolute right-2 flex size-7 items-center justify-center rounded-md text-text-faint transition-colors hover:bg-white/10 hover:text-lemon",
            multiline ? "top-2" : "top-1/2 -translate-y-1/2",
            picking && "bg-white/10 text-lemon"
          )}
        >
          <Braces className="size-4" />
        </button>
      )}
      {picking && <DataPicker sources={sources} onPick={insert} onClose={() => setPicking(false)} />}
    </div>
  );
}

// Pick a source (the trigger or an earlier step), then optionally a path inside its data.
function DataPicker({
  sources,
  onPick,
  onClose,
}: {
  sources: DataSource[];
  onPick: (path: string) => void;
  onClose: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [source, setSource] = useState<DataSource | null>(null);
  const [path, setPath] = useState("");

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

  const cleaned = path.trim().replace(/^\.+|\.+$/g, "");

  return (
    <div
      ref={ref}
      role="dialog"
      aria-label="Insert data"
      className="absolute right-0 top-full z-20 mt-1.5 w-full min-w-64 overflow-hidden rounded-xl border border-border-strong bg-surface-raised shadow-2xl"
    >
      {!source ? (
        <div className="max-h-64 overflow-y-auto p-1.5">
          <p className="px-2.5 pb-1.5 pt-1 text-[10px] font-bold uppercase tracking-wide text-text-faint">
            Insert data from
          </p>
          {sources.map((s) => (
            <button
              key={s.nodeId}
              type="button"
              onClick={() => setSource(s)}
              className="flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left transition-colors hover:bg-white/5"
            >
              <AppLogo appId={s.appId} name={s.appName} className="size-6 rounded-md" />
              <span className="min-w-0 flex-1 truncate text-sm font-medium">{s.label}</span>
            </button>
          ))}
        </div>
      ) : (
        <form
          className="space-y-3 p-3"
          onSubmit={(e) => {
            e.preventDefault();
            onPick(cleaned ? `${source.path}.${cleaned}` : source.path);
          }}
        >
          <button
            type="button"
            onClick={() => setSource(null)}
            className="inline-flex items-center gap-1 text-xs font-medium text-text-muted hover:text-text"
          >
            <ChevronLeft className="size-3.5" />
            {source.label}
          </button>
          <div>
            <label htmlFor="data-path" className="mb-1.5 block text-xs text-text-muted">
              Field <span className="text-text-faint">(optional; leave empty for all of it)</span>
            </label>
            <input
              id="data-path"
              autoFocus
              value={path}
              onChange={(e) => setPath(e.target.value)}
              placeholder={source.path === "trigger.body" ? "order.id" : "id"}
              spellCheck={false}
              className="h-9 w-full rounded-lg border border-border-strong bg-surface-sunken px-3 font-mono text-xs text-text outline-none focus:border-lemon"
            />
            <p className="mt-1.5 font-mono text-[11px] text-text-faint">
              {`{{${source.path}${cleaned ? "." + cleaned : ""}}}`}
            </p>
          </div>
          <button
            type="submit"
            className="w-full rounded-lg bg-lemon py-2 text-xs font-bold text-black transition-colors hover:bg-lemon-dim"
          >
            Insert
          </button>
        </form>
      )}
    </div>
  );
}

type Row = { id: number; key: string; value: string };

// Row identity for React keys only; never saved.
let rowSeq = 0;
const newRow = (key = "", value = ""): Row => ({ id: ++rowSeq, key, value });

// Pairs of text boxes, saved as an object; values can hold templates.
function KeyValueInput({
  value,
  sources,
  invalid,
  onChange,
}: {
  value: unknown;
  sources: DataSource[];
  invalid: boolean;
  onChange: (value: unknown) => void;
}) {
  const [rows, setRows] = useState<Row[]>(() => {
    const entries = value && typeof value === "object" ? Object.entries(value as Record<string, unknown>) : [];
    const initial = entries.map(([k, v]) => newRow(k, v == null ? "" : String(v)));
    return initial.length > 0 ? initial : [newRow()];
  });

  function update(next: Row[]) {
    setRows(next);
    const obj: Record<string, string> = {};
    for (const r of next) if (r.key.trim()) obj[r.key.trim()] = r.value;
    onChange(Object.keys(obj).length > 0 ? obj : undefined);
  }

  return (
    <div className="space-y-2">
      {rows.map((row, i) => (
        <div key={row.id} className="flex items-start gap-2">
          <input
            aria-label={`Key ${i + 1}`}
            value={row.key}
            placeholder="Key"
            spellCheck={false}
            onChange={(e) => update(rows.map((r) => (r.id === row.id ? { ...r, key: e.target.value } : r)))}
            className={cn(
              "h-11 w-[38%] shrink-0 rounded-lg border bg-surface-sunken px-3 text-sm text-text placeholder:text-text-faint outline-none focus:border-lemon",
              invalid ? "border-red-500/60" : "border-border-strong"
            )}
          />
          <div className="min-w-0 flex-1">
            <TemplateInput
              ariaLabel={`Value ${i + 1}`}
              value={row.value}
              placeholder="Value"
              sources={sources}
              invalid={invalid}
              onChange={(v) => update(rows.map((r) => (r.id === row.id ? { ...r, value: v } : r)))}
            />
          </div>
          <button
            type="button"
            aria-label={`Remove row ${i + 1}`}
            onClick={() => {
              const rest = rows.filter((r) => r.id !== row.id);
              update(rest.length > 0 ? rest : [newRow()]);
            }}
            className="flex size-11 shrink-0 items-center justify-center rounded-lg text-text-faint transition-colors hover:bg-white/5 hover:text-text"
          >
            <X className="size-4" />
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={() => setRows([...rows, newRow()])}
        className="inline-flex items-center gap-1.5 text-xs font-semibold text-lemon hover:underline"
      >
        <Plus className="size-3.5" />
        Add row
      </button>
    </div>
  );
}

// JSON when it parses (saved as that value, so {"a": 1} stays structured), otherwise the text
// as-is. Keeps its own text so reformatting never moves the cursor while typing.
function JsonInput({
  id,
  field,
  value,
  sources,
  invalid,
  onChange,
}: {
  id: string;
  field: CatalogField;
  value: unknown;
  sources: DataSource[];
  invalid: boolean;
  onChange: (value: unknown) => void;
}) {
  const [text, setText] = useState(() =>
    value == null ? "" : typeof value === "string" ? value : JSON.stringify(value, null, 2)
  );
  const parsed = parseJson(text);

  return (
    <div>
      <TemplateInput
        id={id}
        multiline
        mono
        rows={5}
        value={text}
        placeholder={field.placeholder ?? '{"key": "value"}'}
        sources={sources}
        invalid={invalid}
        onChange={(t) => {
          setText(t);
          if (t.trim() === "") onChange(undefined);
          else {
            const p = parseJson(t);
            onChange(p.ok ? p.value : t);
          }
        }}
      />
      {text.trim() !== "" && (
        <p className={cn("mt-1.5 text-xs", parsed.ok ? "text-emerald-400" : "text-text-faint")}>
          {parsed.ok ? "Valid JSON" : "Not JSON, so it's used as plain text"}
        </p>
      )}
    </div>
  );
}

function parseJson(text: string): { ok: true; value: unknown } | { ok: false } {
  try {
    return { ok: true, value: JSON.parse(text) };
  } catch {
    return { ok: false };
  }
}

function ReadOnlyValue({ field, value }: { field: CatalogField; value: unknown }) {
  let shown: string;
  if (isEmptyValue(value)) shown = "Not set";
  else if (field.type === "boolean") shown = value === true ? "Yes" : "No";
  else if (field.type === "select") shown = field.options?.find((o) => o.value === value)?.label ?? String(value);
  else if (typeof value === "string" || typeof value === "number") shown = String(value);
  else shown = JSON.stringify(value, null, 2);

  return (
    <pre
      className={cn(
        "whitespace-pre-wrap break-words rounded-xl border border-border-strong bg-surface-sunken px-4 py-3 font-sans text-sm",
        isEmptyValue(value) ? "text-text-faint" : "text-text",
        (field.type === "json" || field.type === "keyvalue") && "font-mono text-xs"
      )}
    >
      {shown}
    </pre>
  );
}

// Which earlier steps a value reads, in words, so {{steps.action-1a2b.output.id}} is legible.
function UsedData({ value, sources }: { value: unknown; sources: DataSource[] }) {
  const paths = templatePaths(value);
  if (paths.length === 0) return null;
  return (
    <ul className="mt-1.5 space-y-0.5">
      {paths.map((p) => {
        const d = describePath(p, sources);
        return (
          <li key={p} className={cn("flex items-center gap-1.5 text-xs", d.known ? "text-text-muted" : "text-amber-400")}>
            <Braces className="size-3 shrink-0" />
            {d.known ? `Uses ${d.label}` : `Uses ${p}, which isn't a step before this one`}
          </li>
        );
      })}
    </ul>
  );
}
