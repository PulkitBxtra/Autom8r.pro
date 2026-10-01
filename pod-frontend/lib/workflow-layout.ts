import { logicOutputs } from "@/lib/logic";
import { TRIGGER_NODE_ID, type WorkflowEdge, type WorkflowNode } from "@/lib/workflow-graph";

// Where everything on the canvas goes. Users can't move steps: each sits a row below the step
// before it. A Logic step (If / Else, Switch, Paths) leaves from one point that splits into a
// column per path, each as wide as what's in it, and the paths meet again at one step below
// the longest of them. Until a step is added there, that meeting point is an add-step button.

export const COLUMN_WIDTH = 300;
export const ROW_HEIGHT = 190;
// Node card is w-64 (16rem); the add-step button is size-8 (2rem).
export const NODE_WIDTH = 256;
export const PLACEHOLDER_WIDTH = 32;
// How far below a split, and above a meeting point, lines turn.
const TURN = 28;

export type Point = { x: number; y: number };

// A line's shape: columnX is the column it runs down (a Logic step's path), label its path name.
export type EdgeRoute = { columnX?: number; label?: string };

// What the + on a pending line adds: a step after parentId (on one of its paths), or the step
// a Logic step's paths meet at.
export type EdgeAdd = { parentId: string; handle?: string } | { mergeOf: string };

export type LayoutPlaceholder = {
  id: string;
  // Centre of the button, top of its row.
  x: number;
  y: number;
  parentId: string;
  handle?: string;
  mergeOf?: string;
};

export type GhostEdge = {
  id: string;
  source: string;
  sourceHandle: string | null;
  target: string;
  route: EdgeRoute;
  add?: EdgeAdd;
};

type End = { source: string; handle?: string; add: EdgeAdd };
type Column = { handle: string | null; label?: string; kids: WorkflowEdge[] };

export function mergePlaceholderId(blockId: string) {
  return `placeholder-merge-${blockId}`;
}

export function layoutWorkflow(nodes: WorkflowNode[], edges: WorkflowEdge[]) {
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const real = edges.filter((e) => byId.has(e.source) && byId.has(e.target));
  const out = new Map<string, WorkflowEdge[]>();
  const inc = new Map<string, WorkflowEdge[]>();
  for (const e of real) {
    out.set(e.source, [...(out.get(e.source) ?? []), e]);
    inc.set(e.target, [...(inc.get(e.target) ?? []), e]);
  }
  const outputsOf = (id: string) => {
    const n = byId.get(id);
    return n ? logicOutputs(n.data.item, n.data.parameters) : null;
  };

  // The trigger first; steps cut off from it (after deleting a Logic step) follow.
  const roots = [
    ...nodes.filter((n) => n.id === TRIGGER_NODE_ID),
    ...nodes.filter((n) => n.id !== TRIGGER_NODE_ID && !inc.has(n.id)),
  ].map((n) => n.id);

  const merges = findMerges(roots, out, inc, outputsOf);
  const mergeOwner = new Map([...merges].map(([block, merge]) => [merge, block]));

  // Each other step is drawn under the first step leading to it.
  const placedBy = new Map<string, WorkflowEdge>();
  for (const n of nodes) {
    const first = inc.get(n.id)?.[0];
    if (first && !mergeOwner.has(n.id)) placedBy.set(n.id, first);
  }
  const kidEdges = (id: string) => (out.get(id) ?? []).filter((e) => placedBy.get(e.target) === e);
  const hasHandleEdge = (id: string, handle: string) => (out.get(id) ?? []).some((e) => e.sourceHandle === handle);

  const columnsOf = (id: string): Column[] => {
    const kids = kidEdges(id);
    const outputs = outputsOf(id);
    if (!outputs) return [{ handle: null, kids }];
    const cols: Column[] = outputs.map((o) => ({
      handle: o.id,
      label: o.label,
      kids: kids.filter((e) => e.sourceHandle === o.id),
    }));
    const stray = kids.filter((e) => !outputs.some((o) => o.id === e.sourceHandle));
    if (stray.length > 0) cols.push({ handle: null, kids: stray });
    return cols;
  };

  // Where a chain of steps is left open: the steps (or paths) nothing follows yet.
  const chainEnds = (id: string, expand: boolean): End[] => {
    if (!outputsOf(id)) {
      if (!out.has(id)) return [{ source: id, add: { parentId: id } }];
      return kidEdges(id).flatMap((e) => chainEnds(e.target, expand));
    }
    const merge = merges.get(id);
    if (merge) return chainEnds(merge, expand);
    const ends = openEnds(id, expand);
    if (expand || ends.length === 0) return ends;
    return [{ source: mergePlaceholderId(id), add: { mergeOf: id } }];
  };
  // A Logic step's open ends: empty paths and the open ends of each path. With expand, ends
  // inside nested Logic steps are listed themselves instead of as those steps' meeting points.
  const openEnds = (blockId: string, expand: boolean): End[] =>
    columnsOf(blockId).flatMap((col) => {
      if (col.handle && !hasHandleEdge(blockId, col.handle)) {
        return [{ source: blockId, handle: col.handle, add: { parentId: blockId, handle: col.handle } }];
      }
      return col.kids.flatMap((e) => chainEnds(e.target, expand));
    });
  const pendingMerge = new Map<string, boolean>();
  const hasPendingMerge = (id: string): boolean => {
    if (!pendingMerge.has(id)) pendingMerge.set(id, !merges.has(id) && !!outputsOf(id) && openEnds(id, false).length > 0);
    return pendingMerge.get(id)!;
  };

  // Steps whose open end runs to a pending meeting point get no add-step button of their own.
  const leadsToMerge = new Set<string>();
  for (const n of nodes) {
    if (hasPendingMerge(n.id)) openEnds(n.id, false).forEach((e) => !e.handle && leadsToMerge.add(e.source));
  }

  // Size in columns and rows of a step and everything placed below it.
  const sizes = new Map<string, { w: number; h: number }>();
  const colWidth = (col: Column) => Math.max(1, col.kids.reduce((s, e) => s + size(e.target).w, 0));
  const colHeight = (col: Column) => col.kids.reduce((m, e) => Math.max(m, size(e.target).h), 0);
  const size = (id: string): { w: number; h: number } => {
    const cached = sizes.get(id);
    if (cached) return cached;
    const cols = columnsOf(id);
    let result;
    if (!outputsOf(id)) {
      result = { w: colWidth(cols[0]), h: 1 + colHeight(cols[0]) };
    } else {
      const merge = merges.get(id);
      const after = merge ? size(merge) : hasPendingMerge(id) ? { w: 1, h: 1 } : { w: 0, h: 0 };
      const blockW = cols.reduce((s, c) => s + colWidth(c), 0);
      result = { w: Math.max(blockW, after.w), h: 1 + Math.max(1, ...cols.map(colHeight)) + after.h };
    }
    sizes.set(id, result);
    return result;
  };

  const positions = new Map<string, Point>();
  const placeholders: LayoutPlaceholder[] = [];
  const ghostEdges: GhostEdge[] = [];
  const routes = new Map<string, EdgeRoute>();
  const centre = (left: number, w: number) => (left + w / 2) * COLUMN_WIDTH;

  const addNext = (parentId: string, handle: string | undefined, x: number, row: number, label?: string) => {
    const id = handle ? `placeholder-${parentId}-${handle}` : `placeholder-${parentId}`;
    placeholders.push({ id, x, y: row * ROW_HEIGHT, parentId, handle });
    ghostEdges.push({
      id: handle ? `placeholder-edge-${parentId}-${handle}` : `placeholder-edge-${parentId}`,
      source: parentId,
      sourceHandle: handle ?? null,
      target: id,
      route: handle ? { columnX: x, label } : {},
    });
  };

  const place = (id: string, left: number, row: number) => {
    const { w } = size(id);
    const cx = centre(left, w);
    positions.set(id, { x: cx - NODE_WIDTH / 2, y: row * ROW_HEIGHT });
    const cols = columnsOf(id);

    if (!outputsOf(id)) {
      let x = left + (w - colWidth(cols[0])) / 2;
      for (const e of cols[0].kids) {
        place(e.target, x, row + 1);
        x += size(e.target).w;
      }
      if (!out.has(id) && !leadsToMerge.has(id)) addNext(id, undefined, cx, row + 1);
      return;
    }

    const merge = merges.get(id);
    const pending = hasPendingMerge(id);
    const columnX = new Map<string, number>();
    let x = left + (w - cols.reduce((s, c) => s + colWidth(c), 0)) / 2;
    for (const col of cols) {
      const cw = colWidth(col);
      const colX = centre(x, cw);
      if (col.handle) {
        columnX.set(col.handle, colX);
        for (const e of out.get(id) ?? []) {
          if (e.sourceHandle === col.handle) routes.set(e.id, { columnX: colX, label: col.label });
        }
        if (!hasHandleEdge(id, col.handle) && !pending) addNext(id, col.handle, colX, row + 1, col.label);
      }
      let kx = x;
      for (const e of col.kids) {
        place(e.target, kx, row + 1);
        kx += size(e.target).w;
      }
      x += cw;
    }

    const mergeRow = row + 1 + Math.max(1, ...cols.map(colHeight));
    if (merge) place(merge, left + (w - size(merge).w) / 2, mergeRow);
    if (pending) {
      const pid = mergePlaceholderId(id);
      placeholders.push({ id: pid, x: cx, y: mergeRow * ROW_HEIGHT, parentId: id, mergeOf: id });
      const labels = new Map(cols.map((c) => [c.handle, c.label]));
      for (const end of openEnds(id, false)) {
        ghostEdges.push({
          id: `ghost-${end.source}-${end.handle ?? ""}-${pid}`,
          source: end.source,
          sourceHandle: end.handle ?? null,
          target: pid,
          route: end.handle ? { columnX: columnX.get(end.handle), label: labels.get(end.handle) } : {},
          add: end.add,
        });
      }
    }
  };

  let left = roots.length > 0 ? -size(roots[0]).w / 2 : 0;
  roots.forEach((r, i) => {
    place(r, left, i === 0 ? 0 : 1);
    left += size(r).w;
  });

  return {
    positions,
    placeholders,
    ghostEdges,
    routes,
    merges,
    // Every open end of a Logic step's paths, for joining them at a new meeting step.
    openEndsOf: (blockId: string) => openEnds(blockId, true),
  };
}

// The step each Logic step's paths meet at: a step with several incoming lines that every
// path from that Logic step must pass through first (its immediate dominator).
function findMerges(
  roots: string[],
  out: Map<string, WorkflowEdge[]>,
  inc: Map<string, WorkflowEdge[]>,
  outputsOf: (id: string) => unknown
) {
  const ROOT = "\u0000root";
  const succ = (id: string) => (id === ROOT ? roots : (out.get(id) ?? []).map((e) => e.target));
  const pred = (id: string) => [
    ...(inc.get(id) ?? []).map((e) => e.source),
    ...(roots.includes(id) ? [ROOT] : []),
  ];

  const postorder: string[] = [];
  const seen = new Set<string>();
  const visit = (id: string) => {
    seen.add(id);
    for (const s of succ(id)) if (!seen.has(s)) visit(s);
    postorder.push(id);
  };
  visit(ROOT);
  const rpo = [...postorder].reverse();
  const index = new Map(rpo.map((id, i) => [id, i]));

  // Cooper, Harvey and Kennedy's iterative dominator algorithm.
  const idom = new Map<string, string>([[ROOT, ROOT]]);
  const intersect = (a: string, b: string) => {
    while (a !== b) {
      while (index.get(a)! > index.get(b)!) a = idom.get(a)!;
      while (index.get(b)! > index.get(a)!) b = idom.get(b)!;
    }
    return a;
  };
  for (let changed = true; changed; ) {
    changed = false;
    for (const id of rpo) {
      if (id === ROOT) continue;
      const ps = pred(id).filter((p) => idom.has(p));
      if (ps.length === 0) continue;
      const next = ps.slice(1).reduce((d, p) => intersect(p, d), ps[0]);
      if (idom.get(id) !== next) {
        idom.set(id, next);
        changed = true;
      }
    }
  }

  const merges = new Map<string, string>();
  for (const id of rpo) {
    if ((inc.get(id)?.length ?? 0) < 2) continue;
    const d = idom.get(id);
    if (d && d !== ROOT && outputsOf(d) && !merges.has(d)) merges.set(d, id);
  }
  return merges;
}

// A line's path through its turns, with rounded corners, plus where its path name and + go.
export function routePath(sx: number, sy: number, tx: number, ty: number, route: EdgeRoute | undefined) {
  const points: Point[] = [{ x: sx, y: sy }];
  let x = sx;
  let top = sy;
  if (route?.columnX !== undefined && route.columnX !== sx) {
    points.push({ x: sx, y: sy + TURN }, { x: route.columnX, y: sy + TURN });
    x = route.columnX;
    top = sy + TURN;
  }
  let bottom = ty;
  if (x !== tx) {
    points.push({ x, y: ty - TURN }, { x: tx, y: ty - TURN });
    bottom = ty - TURN;
  }
  points.push({ x: tx, y: ty });

  const labelY = route?.label ? top + 16 : undefined;
  const addY = labelY !== undefined ? Math.max(labelY + 28, (top + bottom) / 2) : (top + bottom) / 2;
  return { path: roundedPath(points, 10), x, labelY, addY };
}

function roundedPath(points: Point[], radius: number) {
  let d = `M ${points[0].x} ${points[0].y}`;
  for (let i = 1; i < points.length - 1; i++) {
    const [p, c, n] = [points[i - 1], points[i], points[i + 1]];
    const d1 = Math.hypot(c.x - p.x, c.y - p.y);
    const d2 = Math.hypot(n.x - c.x, n.y - c.y);
    const r = Math.min(radius, d1 / 2, d2 / 2);
    if (r <= 0) continue;
    const a = { x: c.x + ((p.x - c.x) / d1) * r, y: c.y + ((p.y - c.y) / d1) * r };
    const b = { x: c.x + ((n.x - c.x) / d2) * r, y: c.y + ((n.y - c.y) / d2) * r };
    d += ` L ${a.x} ${a.y} Q ${c.x} ${c.y} ${b.x} ${b.y}`;
  }
  const last = points[points.length - 1];
  return `${d} L ${last.x} ${last.y}`;
}
