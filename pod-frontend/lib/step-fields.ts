import type { CatalogField } from "@/lib/types";

// Where a step can read data from: the trigger or a step that always runs before it. `path` is
// the template prefix the engine resolves ({{trigger.body...}}, {{steps.<id>.output...}}).
export type DataSource = {
  nodeId: string;
  label: string;
  appId?: string;
  appName: string;
  path: string;
};

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

// Required fields that have no value yet.
export function missingRequired(fields: CatalogField[] | undefined, params: Record<string, unknown> | undefined) {
  return (fields ?? []).filter((f) => f.required && f.type !== "boolean" && isEmptyValue(params?.[f.key]));
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
  return { label: rest ? `${source.label} → ${rest}` : source.label, known: true };
}
