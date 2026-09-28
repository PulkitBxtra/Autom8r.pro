import type { AppAction, AppTrigger } from "@/lib/types";

// Logic steps (Switch, Paths, If/Else, Filter): their settings' shapes and the outputs they
// branch into. Mirrors pod-backend's StepSettingsValidator and pod-processor's LogicHandler.

export type Condition = { left: string; op: string; right: string };
export type ConditionGroup = { match: "all" | "any"; conditions: Condition[] };
export type LogicPath = ConditionGroup & { id: string; name: string };

export const OPERATORS: { value: string; label: string; unary?: boolean }[] = [
  { value: "equals", label: "equals" },
  { value: "not_equals", label: "doesn't equal" },
  { value: "contains", label: "contains" },
  { value: "not_contains", label: "doesn't contain" },
  { value: "starts_with", label: "starts with" },
  { value: "ends_with", label: "ends with" },
  { value: "gt", label: "is greater than" },
  { value: "gte", label: "is at least" },
  { value: "lt", label: "is less than" },
  { value: "lte", label: "is at most" },
  { value: "in_list", label: "is one of (comma-separated)" },
  { value: "is_empty", label: "is empty", unary: true },
  { value: "is_not_empty", label: "is not empty", unary: true },
  { value: "is_true", label: "is true", unary: true },
  { value: "is_false", label: "is false", unary: true },
];

export const isUnary = (op: string) => OPERATORS.some((o) => o.value === op && o.unary);
export const operatorLabel = (op: string) => OPERATORS.find((o) => o.value === op)?.label ?? op;

export function emptyCondition(): Condition {
  return { left: "", op: "equals", right: "" };
}

export function newPathId(taken: string[] = []) {
  for (;;) {
    const id = `p_${Math.random().toString(36).slice(2, 8)}`;
    if (!taken.includes(id)) return id;
  }
}

const blank = (v: unknown) => v == null || String(v).trim() === "";

// A condition still missing the data to check, or the value to compare to.
export function conditionIncomplete(c: Partial<Condition>) {
  return blank(c.left) || (!isUnary(c.op ?? "") && blank(c.right));
}

export function groupIncomplete(value: unknown) {
  const g = value as Partial<ConditionGroup> | null;
  return !g || !Array.isArray(g.conditions) || g.conditions.length === 0 || g.conditions.some(conditionIncomplete);
}

export function pathsIncomplete(value: unknown) {
  return (
    !Array.isArray(value) ||
    value.length === 0 ||
    value.some((p: Partial<LogicPath>) => blank(p?.name) || groupIncomplete(p))
  );
}

export type LogicOutput = { id: string; label: string };

// The outputs a step's connections leave from, or null for a step with just one (anything that
// isn't a Switch, Paths or If/Else; a Filter's single output needs no name on the canvas).
export function logicOutputs(
  item: AppTrigger | AppAction | undefined,
  parameters: Record<string, unknown> | undefined
): LogicOutput[] | null {
  if (!item || !("handler" in item)) return null;
  switch (item.handler) {
    case "logic.if_else":
      return [
        { id: "if", label: "If" },
        { id: "else", label: "Else" },
      ];
    case "logic.switch":
    case "logic.paths": {
      const paths = Array.isArray(parameters?.paths) ? (parameters!.paths as LogicPath[]) : [];
      const outputs = paths.map((p) => ({ id: p.id, label: p.name || "Untitled path" }));
      return item.handler === "logic.switch" ? [...outputs, { id: "otherwise", label: "Otherwise" }] : outputs;
    }
    default:
      return null;
  }
}
