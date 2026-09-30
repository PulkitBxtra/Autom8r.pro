import type { AppAction, AppTrigger, CatalogField, CatalogOutput, OutputType } from "@/lib/types";
import { groupIncomplete, pathsIncomplete } from "@/lib/logic";

// Where a step can read data from: the trigger or a step that always runs before it. `path` is
// the template prefix the engine resolves ({{trigger.body...}}, {{steps.<id>.output...}}).
export type DataSource = {
  nodeId: string;
  label: string;
  appId?: string;
  appName: string;
  path: string;
  // What its data holds, from the catalog; empty when not declared (any path may be typed).
  outputs: CatalogOutput[];
};

const OUTPUT_TYPES: OutputType[] = ["text", "number", "boolean", "datetime", "object", "list", "any"];

// Outputs a user declared on a step (a Code step's Outputs setting), in catalog shape; rows
// without a name yet are left out.
export function declaredOutputs(value: unknown): CatalogOutput[] {
  if (!Array.isArray(value)) return [];
  return value
    .filter((o): o is Record<string, unknown> => !!o && typeof o === "object" && typeof o.key === "string" && o.key.trim() !== "")
    .map((o) => ({
      key: String(o.key).trim(),
      label: typeof o.label === "string" && o.label.trim() ? o.label.trim() : String(o.key).trim(),
      type: OUTPUT_TYPES.includes(o.type as OutputType) ? (o.type as OutputType) : "any",
      fields: declaredOutputs(o.fields),
    }));
}

// What a step's data holds: the catalog's outputs, after the ones its settings declare.
export function stepOutputs(item: AppTrigger | AppAction, parameters: Record<string, unknown> | undefined): CatalogOutput[] {
  const from = "outputsFrom" in item ? item.outputsFrom : null;
  if (!from) return item.outputs ?? [];
  return [...declaredOutputs(parameters?.[from]), ...(item.outputs ?? [])];
}

// One choice in the data picker: a declared output, or a field of each item of a list (read
// from its first item, e.g. files.0.name).
export type OutputChoice = { path: string; label: string; type: CatalogOutput["type"]; depth: number };

export function outputChoices(outputs: CatalogOutput[], prefix = "", labelPrefix = "", depth = 0): OutputChoice[] {
  const out: OutputChoice[] = [];
  for (const o of outputs) {
    const path = prefix + o.key;
    const label = labelPrefix + o.label;
    out.push({ path, label, type: o.type, depth });
    if (o.fields.length > 0) {
      const list = o.type === "list";
      out.push(...outputChoices(o.fields, path + (list ? ".0." : "."), label + (list ? " (first) → " : " → "), depth + 1));
    }
  }
  return out;
}

// The declared output a path inside a source's data names, in words ("Issue URL"). Unknown
// when the source declares outputs and the path isn't one of them.
function outputLabel(rest: string, outputs: CatalogOutput[]): { label: string; known: boolean } {
  const parts = rest.split(".");
  const labels: string[] = [];
  let level = outputs;
  let i = 0;
  while (i < parts.length) {
    const o = level.find((x) => x.key === parts[i]);
    if (!o) return { label: rest, known: false };
    labels.push(o.label);
    i++;
    if (o.type === "list" && i < parts.length) {
      // Past a list comes an item's index.
      if (!/^\d+$/.test(parts[i])) return { label: rest, known: false };
      labels[labels.length - 1] += ` #${Number(parts[i]) + 1}`;
      i++;
    }
    if (o.fields.length === 0) {
      // Only "any" (or an object with nothing declared) has more inside; plain values don't.
      if (i < parts.length && o.type !== "any" && o.type !== "object") return { label: rest, known: false };
      break;
    }
    level = o.fields;
  }
  const tail = parts.slice(i).join(".");
  return { label: [...labels, ...(tail ? [tail] : [])].join(" → "), known: true };
}

// What pod-backend sends in place of a saved secret. Saving it back unchanged keeps the secret.
export const SECRET_MASK = "••••••••";

// Same rule as pod-backend's SecretMasker: header/key names whose values are secret.
export function isSensitiveKey(key: string) {
  return /auth|cookie|token|secret|passw|api[-_]?key|private|session|signature/i.test(key);
}

// Parameters a freshly chosen trigger/action starts with: every field's default.
export function defaultParameters(fields: CatalogField[]): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  for (const f of fields) {
    if (f.defaultValue !== null && f.defaultValue !== undefined) params[f.key] = f.defaultValue;
  }
  return params;
}

export function isEmptyValue(value: unknown) {
  if (value === null || value === undefined) return true;
  if (typeof value === "string") return value.trim() === "";
  if (typeof value === "object" && !Array.isArray(value)) return Object.keys(value).length === 0;
  return false;
}

// Required fields that have no value yet (for Logic fields: a condition still half filled in).
export function missingRequired(fields: CatalogField[] | undefined, params: Record<string, unknown> | undefined) {
  return (fields ?? []).filter((f) => {
    const v = params?.[f.key];
    if (f.type === "conditions") return f.required && groupIncomplete(v);
    if (f.type === "paths") return f.required && pathsIncomplete(v);
    return f.required && f.type !== "boolean" && isEmptyValue(v);
  });
}

const TEMPLATE = /\{\{\s*([^{}]+?)\s*\}\}/g;

// The data paths a value reads, e.g. "steps.a1.output.number", anywhere inside it.
export function templatePaths(value: unknown): string[] {
  const out = new Set<string>();
  const walk = (v: unknown) => {
    if (typeof v === "string") for (const m of v.matchAll(TEMPLATE)) out.add(m[1]);
    else if (Array.isArray(v)) v.forEach(walk);
    else if (v && typeof v === "object") Object.values(v).forEach(walk);
  };
  walk(value);
  return [...out];
}

// "Step 2 · Create Issue → number" for a path, using the step's name instead of its id.
export function describePath(path: string, sources: DataSource[]) {
  const source = [...sources]
    .sort((a, b) => b.path.length - a.path.length)
    .find((s) => path === s.path || path.startsWith(s.path + "."));
  if (!source) return { label: path, known: false };
  const rest = path.slice(source.path.length + 1);
  if (!rest) return { label: source.label, known: true };
  if (source.outputs.length === 0) return { label: `${source.label} → ${rest}`, known: true };
  const o = outputLabel(rest, source.outputs);
  // A path the step doesn't declare still resolves at run time; flag it rather than block it.
  return { label: `${source.label} → ${o.label}`, known: true, undeclared: !o.known };
}
