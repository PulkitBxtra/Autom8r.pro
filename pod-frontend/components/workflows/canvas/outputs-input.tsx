"use client";

import { Plus, X } from "lucide-react";
import { cn } from "@/lib/utils";
import { outputKeyProblem } from "@/lib/code";
import type { OutputType } from "@/lib/types";

export const TYPE_NAMES: Record<OutputType, string> = {
  text: "Text",
  number: "Number",
  boolean: "Yes/no",
  datetime: "Date",
  object: "Object",
  list: "List",
  any: "Any",
};

// Outputs deeper than this can't hold fields (same limit as pod-backend).
const MAX_DEPTH = 3;

// One output as saved in the step's settings. label and fields are optional.
export type DeclaredOutput = { key: string; label?: string; type: OutputType; fields?: DeclaredOutput[] };

export function asDeclared(value: unknown): DeclaredOutput[] {
  return Array.isArray(value) ? (value as DeclaredOutput[]) : [];
}

// What a step returns, declared by the user (a Code step's Outputs): name, label and type per
// row; objects and lists can list their fields (for a list: what each item holds). These are
// what later steps pick from in the data list.
export function OutputsInput({
  value,
  taken,
  onChange,
}: {
  value: unknown;
  // Names the step already has (e.g. "logs"), which a declared output can't reuse.
  taken: string[];
  onChange: (value: unknown) => void;
}) {
  const rows = asDeclared(value);
  return (
    <OutputRows
      rows={rows}
      depth={1}
      taken={taken}
      prefix=""
      onChange={(next) => onChange(next.length > 0 ? next : undefined)}
    />
  );
}

function OutputRows({
  rows,
  depth,
  taken,
  prefix,
  onChange,
}: {
  rows: DeclaredOutput[];
  depth: number;
  taken: string[];
  // For accessible names of nested rows: "Output 2 field 1".
  prefix: string;
  onChange: (rows: DeclaredOutput[]) => void;
}) {
  const update = (i: number, patch: Partial<DeclaredOutput>) =>
    onChange(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)));

  return (
    <div className="space-y-2">
      {rows.map((row, i) => {
        const name = `${prefix}${prefix ? " field" : "Output"} ${i + 1}`;
        const others = rows.filter((_, j) => j !== i).map((r) => r.key.trim());
        const problem = row.key.trim() || row.label?.trim() ? outputKeyProblem(row.key, [...others, ...taken]) : null;
        const canNest = (row.type === "object" || row.type === "list") && depth < MAX_DEPTH;
        return (
          <div key={i} data-output={`${prefix}${i}`}>
            <div className="flex items-start gap-2">
              <input
                aria-label={`${name} name`}
                value={row.key}
                placeholder="name"
                spellCheck={false}
                onChange={(e) => update(i, { key: e.target.value })}
                className={cn(
                  "h-10 w-[30%] min-w-0 shrink-0 rounded-lg border bg-surface-sunken px-3 font-mono text-xs text-text placeholder:text-text-faint outline-none focus:border-lemon",
                  problem ? "border-red-500/60" : "border-border-strong"
                )}
              />
              <input
                aria-label={`${name} label`}
                value={row.label ?? ""}
                placeholder={row.key.trim() || "Label"}
                maxLength={60}
                onChange={(e) => update(i, { label: e.target.value || undefined })}
                className="h-10 min-w-0 flex-1 rounded-lg border border-border-strong bg-surface-sunken px-3 text-sm text-text placeholder:text-text-faint outline-none focus:border-lemon"
              />
              <select
                aria-label={`${name} type`}
                value={row.type}
                onChange={(e) => {
                  const type = e.target.value as OutputType;
                  update(i, type === "object" || type === "list" ? { type } : { type, fields: undefined });
                }}
                className="h-10 w-24 shrink-0 rounded-lg border border-border-strong bg-surface-sunken px-2 text-sm text-text outline-none focus:border-lemon"
              >
                {(Object.keys(TYPE_NAMES) as OutputType[]).map((t) => (
                  <option key={t} value={t}>
                    {TYPE_NAMES[t]}
                  </option>
                ))}
              </select>
              <button
                type="button"
                aria-label={`Remove ${name.toLowerCase()}`}
                onClick={() => onChange(rows.filter((_, j) => j !== i))}
                className="flex size-10 shrink-0 items-center justify-center rounded-lg text-text-faint transition-colors hover:bg-white/5 hover:text-text"
              >
                <X className="size-4" />
              </button>
            </div>
            {problem && <p className="mt-1 text-xs text-red-400">{problem}</p>}
            {canNest && (
              <div className="ml-3 mt-2 border-l border-border pl-3">
                <p className="mb-1.5 text-[11px] text-text-faint">
                  {row.type === "list" ? "What each item holds (leave empty for a list of plain values)" : "What it holds"}
                </p>
                <OutputRows
                  rows={row.fields ?? []}
                  depth={depth + 1}
                  taken={[]}
                  prefix={name}
                  onChange={(fields) => update(i, { fields: fields.length > 0 ? fields : undefined })}
                />
              </div>
            )}
          </div>
        );
      })}
      <button
        type="button"
        onClick={() => onChange([...rows, { key: "", type: "text" }])}
        className="inline-flex items-center gap-1.5 text-xs font-semibold text-lemon hover:underline"
      >
        <Plus className="size-3.5" />
        {depth === 1 ? "Add output" : "Add field"}
      </button>
    </div>
  );
}
