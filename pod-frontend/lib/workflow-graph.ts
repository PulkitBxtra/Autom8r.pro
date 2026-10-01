import type { Edge, Node } from "@xyflow/react";
import type {
  App,
  AppAction,
  AppTrigger,
  GraphNode,
  Workflow,
  WorkflowGraph,
} from "@/lib/types";
import type { Catalog } from "@/lib/catalog";
import { missingRequired, stepOutputs, type DataSource } from "@/lib/step-fields";
import { logicOutputs } from "@/lib/logic";
import { layoutWorkflow, ROW_HEIGHT } from "@/lib/workflow-layout";

export type GraphNodeData = {
  kind: "trigger" | "action";
  app?: App;
  item?: AppTrigger | AppAction;
  // Carried through untouched so a load -> save round-trip never drops config.
  parameters?: Record<string, unknown>;
  // The user's connection (account) for the step's app, if it has one chosen.
  connectionId?: string | null;
};

export type WorkflowNode = Node<GraphNodeData, "workflowNode">;
// handle: which output of a Logic step the new step would hang off (undefined for ordinary steps).
// mergeOf: set on the button where a Logic step's paths meet; it adds the step they meet at.
export type PlaceholderNodeData = { parentId: string; handle?: string; mergeOf?: string };
export type PlaceholderNode = Node<PlaceholderNodeData, "placeholderNode">;
export type CanvasNode = WorkflowNode | PlaceholderNode;
export type WorkflowEdge = Edge;

export const TRIGGER_NODE_ID = "trigger";

// Short, since templates that read a step's output spell out its id ({{steps.<id>.output}}).
export function createActionNodeId(existing: Iterable<string> = []) {
  const taken = new Set(existing);
  for (;;) {
    const id = `action-${crypto.randomUUID().slice(0, 8)}`;
    if (!taken.has(id)) return id;
  }
}

// Keeps a step's outgoing edges on outputs it still has, after its settings changed: edges of a
// deleted path go (whatever hung off them is left unconnected, which saving then points out);
// an edge with no output name moves onto the first output when the step becomes a Switch,
// Paths or If/Else, and loses its name when the step stops being one.
export function reconcileOutputs(
  edges: WorkflowEdge[],
  nodeId: string,
  outputs: { id: string }[] | null
): WorkflowEdge[] {
  return edges.flatMap((e) => {
    if (e.source !== nodeId) return [e];
    const handle = e.sourceHandle ?? null;
    if (!outputs) {
      return handle ? [{ ...e, sourceHandle: null, id: edgeId(e.source, e.target) }] : [e];
    }
    if (handle && outputs.some((o) => o.id === handle)) return [e];
    if (handle || outputs.length === 0) return [];
    return [{ ...e, sourceHandle: outputs[0].id, id: edgeId(e.source, e.target, outputs[0].id) }];
  });
}

// When a Logic step's paths already meet at a step, a path added to it (a Switch's new path)
// leads straight there too, instead of ending on its own.
export function joinNewPaths(nodes: WorkflowNode[], edges: WorkflowEdge[], nodeId: string): WorkflowEdge[] {
  const node = nodes.find((n) => n.id === nodeId);
  const outputs = node ? logicOutputs(node.data.item, node.data.parameters) : null;
  const merge = outputs ? layoutWorkflow(nodes, edges).merges.get(nodeId) : undefined;
  if (!outputs || !merge) return edges;
  const missing = outputs.filter((o) => !edges.some((e) => e.source === nodeId && e.sourceHandle === o.id));
  return [
    ...edges,
    ...missing.map((o) => ({
      id: edgeId(nodeId, merge, o.id),
      source: nodeId,
      sourceHandle: o.id,
      target: merge,
      type: "workflowEdge",
    })),
  ];
}

export function edgeId(source: string, target: string, handle?: string | null) {
  return handle ? `edge-${source}-${handle}-${target}` : `edge-${source}-${target}`;
}

export function buildInitialGraph(): { nodes: WorkflowNode[]; edges: WorkflowEdge[] } {
  return {
    nodes: [
      {
        id: TRIGGER_NODE_ID,
        type: "workflowNode",
        position: { x: 0, y: 0 },
        data: { kind: "trigger" },
      },
    ],
    edges: [],
  };
}

// Steps in the order they can run, from the trigger: a step comes after every step leading to
// it, so the step where paths meet comes after all of them. Used only to number steps in the
// UI ("Step 3"); execution order comes from the graph's edges.
export function orderSteps(nodes: WorkflowNode[], edges: WorkflowEdge[]) {
  const trigger = nodes.find((n) => n.id === TRIGGER_NODE_ID);
  const outEdges = new Map<string, string[]>();
  for (const edge of edges) {
    outEdges.set(edge.source, [...(outEdges.get(edge.source) ?? []), edge.target]);
  }

  // Only steps reachable from the trigger are numbered, and only their edges count.
  const reachable = new Set<string>();
  const stack = trigger ? [trigger.id] : [];
  while (stack.length > 0) {
    const id = stack.pop()!;
    if (reachable.has(id)) continue;
    reachable.add(id);
    stack.push(...(outEdges.get(id) ?? []));
  }
  const waiting = new Map<string, number>();
  for (const e of edges) {
    if (reachable.has(e.source)) waiting.set(e.target, (waiting.get(e.target) ?? 0) + 1);
  }

  const orderedIds: string[] = [];
  const queue = trigger ? [trigger.id] : [];
  while (queue.length > 0) {
    const id = queue.shift()!;
    if (id !== TRIGGER_NODE_ID) orderedIds.push(id);
    for (const next of outEdges.get(id) ?? []) {
      const left = waiting.get(next)! - 1;
      waiting.set(next, left);
      if (left === 0) queue.push(next);
    }
  }

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const orderedActionNodes = orderedIds
    .map((id) => byId.get(id))
    .filter((n): n is WorkflowNode => !!n && n.data.kind === "action");

  return { trigger, orderedActionNodes };
}

// What a step can read: the trigger and every step on all paths before it (its ancestors),
// in step order. Anything else might not have run yet, which pod-backend also rejects on save.
export function upstreamSources(
  nodeId: string,
  nodes: WorkflowNode[],
  edges: WorkflowEdge[],
  stepNumbers: Map<string, number>
): DataSource[] {
  const parents = new Map<string, string[]>();
  for (const e of edges) parents.set(e.target, [...(parents.get(e.target) ?? []), e.source]);

  const ancestors = new Set<string>();
  const queue = [...(parents.get(nodeId) ?? [])];
  while (queue.length > 0) {
    const id = queue.shift()!;
    if (ancestors.has(id)) continue;
    ancestors.add(id);
    queue.push(...(parents.get(id) ?? []));
  }

  // A step on only some of the paths before this one (inside an If / Else's path, say) might
  // not have run: this step is still reachable from the trigger without passing through it.
  const children = new Map<string, string[]>();
  for (const e of edges) children.set(e.source, [...(children.get(e.source) ?? []), e.target]);
  const alwaysRuns = (stepId: string) => {
    if (stepId === TRIGGER_NODE_ID) return true;
    const seen = new Set<string>([stepId]);
    const queue = [TRIGGER_NODE_ID];
    while (queue.length > 0) {
      const id = queue.shift()!;
      if (id === nodeId) return false;
      if (seen.has(id)) continue;
      seen.add(id);
      queue.push(...(children.get(id) ?? []));
    }
    return true;
  };

  return nodes
    .filter((n) => ancestors.has(n.id) && n.data.item)
    .sort((a, b) => (stepNumbers.get(a.id) ?? 0) - (stepNumbers.get(b.id) ?? 0))
    .map((n) => ({
      nodeId: n.id,
      label:
        `${stepNumbers.get(n.id) ?? "?"}. ${n.data.item!.name}` +
        (alwaysRuns(n.id) ? "" : " (only if its path ran)"),
      appId: n.data.app?.id,
      appName: n.data.app?.name ?? "",
      path: n.data.kind === "trigger" ? "trigger.body" : `steps.${n.id}.output`,
      outputs: stepOutputs(n.data.item!, n.data.parameters),
    }));
}

// Everything a save would store, for telling whether the editor has unsaved changes.
export function graphSnapshot(nodes: WorkflowNode[], edges: WorkflowEdge[]) {
  return JSON.stringify({
    nodes: nodes
      .map((n) => [n.id, n.data.app?.id, n.data.item?.id, n.data.parameters ?? {}, n.data.connectionId ?? null, n.position.x, n.position.y])
      .sort(),
    edges: edges.map((e) => `${e.source}:${e.sourceHandle ?? ""}>${e.target}`).sort(),
  });
}

function isConfigured(node: WorkflowNode) {
  return !!node.data.app && !!node.data.item;
}

// Converts the canvas into the graph pod-backend stores. Steps added with +
// but never given an app are dropped when nothing hangs off them, so a stray
// click doesn't block saving even if the user never deletes the empty step.
// An unconfigured step in the middle of a path can't be dropped without
// breaking it, so that returns an error naming the step instead.
export function toWorkflowGraph(
  nodes: WorkflowNode[],
  edges: WorkflowEdge[]
): { graph: WorkflowGraph } | { error: string; nodeId: string; tab?: "setup" | "configure" } {
  let keptNodes = [...nodes];
  let keptEdges = [...edges];

  // Repeat, since dropping an empty leaf can leave its empty parent as a new leaf.
  for (;;) {
    const hasChildren = new Set(keptEdges.map((e) => e.source));
    const dropIds = new Set(
      keptNodes
        .filter((n) => n.data.kind === "action" && !isConfigured(n) && !hasChildren.has(n.id))
        .map((n) => n.id)
    );
    if (dropIds.size === 0) break;
    keptNodes = keptNodes.filter((n) => !dropIds.has(n.id));
    keptEdges = keptEdges.filter((e) => !dropIds.has(e.source) && !dropIds.has(e.target));
  }

  const unconfigured = keptNodes.find((n) => !isConfigured(n));
  if (unconfigured) {
    const app = unconfigured.data.app;
    return {
      nodeId: unconfigured.id,
      error:
        unconfigured.data.kind === "trigger"
          ? app
            ? `Choose which ${app.name} event starts this workflow before saving`
            : "Choose a trigger before saving"
          : app
            ? `Choose what the ${app.name} step in the middle of this workflow does`
            : "A step in the middle of this workflow has no app selected",
    };
  }

  // Same rule as pod-backend: an app that needs an account can't be saved without one.
  for (const n of keptNodes) {
    if (n.data.app!.connection === "required" && !n.data.connectionId) {
      return {
        nodeId: n.id,
        tab: "setup",
        error: `"${n.data.item!.name}" needs a ${n.data.app!.name} account; choose one in the step's setup`,
      };
    }
  }

  for (const n of keptNodes) {
    const missing = missingRequired(n.data.item!.fields, n.data.parameters);
    if (missing.length > 0) {
      return {
        nodeId: n.id,
        tab: "configure",
        error: `"${n.data.item!.name}" needs ${missing.map((f) => f.label).join(", ")}`,
      };
    }
  }

  // Stored for reference only; the canvas lays steps out itself.
  const { positions } = layoutWorkflow(keptNodes, keptEdges);
  return {
    graph: {
      nodes: keptNodes.map(
        (n): GraphNode => ({
          id: n.id,
          kind: n.data.kind,
          appName: n.data.app!.name,
          itemId: n.data.item!.id,
          name: n.data.item!.name,
          // Actions run through their catalog handler; triggers have none.
          type: "handler" in n.data.item! ? n.data.item.handler : null,
          parameters: n.data.parameters ?? {},
          position: positions.get(n.id) ?? { x: n.position.x, y: n.position.y },
          appId: n.data.app!.id,
          connectionId: n.data.connectionId ?? null,
        })
      ),
      edges: keptEdges.map((e) => ({
        from: e.source,
        to: e.target,
        condition: (e.data?.condition as string | undefined) ?? null,
        sourceHandle: e.sourceHandle ?? null,
      })),
    },
  };
}

// Catalog lookup with a fallback built from what the graph stored, so a step
// whose app later disappears from the catalog still renders with its name.
function resolveNodeItem(node: GraphNode, catalog: Catalog): { app: App; item: AppTrigger | AppAction } {
  const fallbackApp = unknownApp(node.appId ?? node.appName, node.appName);
  const name = node.name ?? node.itemId;

  if (node.kind === "trigger") {
    const found = catalog.findTrigger(node.itemId);
    return found
      ? { app: found.app, item: found.trigger }
      : { app: fallbackApp, item: { id: node.itemId, name, description: null, fields: [], outputs: [] } };
  }

  const found = catalog.findAction(node.itemId);
  return found
    ? { app: found.app, item: found.action }
    : {
        app: fallbackApp,
        item: { id: node.itemId, name, description: null, handler: node.type ?? "action", fields: [], outputs: [] },
      };
}

function unknownApp(id: string, name: string): App {
  return { id, name, description: null, connection: "none", actions: [], triggers: [] };
}

// Workflows saved since versioning carry their real graph (branches, joins,
// layout). Older ones only have the flat legacy action list, which renders as
// a straight chain.
export function buildGraphFromWorkflow(workflow: Workflow, catalog: Catalog): {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
} {
  if (workflow.graph) {
    return {
      nodes: workflow.graph.nodes.map((n, i) => ({
        id: n.id,
        type: "workflowNode",
        position: n.position ?? { x: 0, y: i * ROW_HEIGHT },
        data: {
          kind: n.kind,
          ...resolveNodeItem(n, catalog),
          parameters: n.parameters ?? {},
          connectionId: n.connectionId ?? null,
        },
      })),
      edges: workflow.graph.edges.map((e) => {
        // A Filter's single output is stored as "pass" but drawn as the step's plain output.
        const handle = e.sourceHandle && e.sourceHandle !== "pass" ? e.sourceHandle : null;
        return {
          id: edgeId(e.from, e.to, handle),
          source: e.from,
          target: e.to,
          sourceHandle: handle,
          type: "workflowEdge",
          ...(e.condition ? { data: { condition: e.condition } } : {}),
        };
      }),
    };
  }

  return buildLegacyGraph(workflow, catalog);
}

export function countActions(workflow: Workflow) {
  if (workflow.graph) {
    return workflow.graph.nodes.filter((n) => n.kind === "action").length;
  }
  return workflow.actions?.length ?? 0;
}

function buildLegacyGraph(workflow: Workflow, catalog: Catalog): {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
} {
  const found = catalog.findTrigger(workflow.triggerId);
  const triggerApp: App = found?.app ?? unknownApp("unknown-app", "Custom trigger");
  const triggerItem: AppTrigger = found?.trigger ?? {
    id: workflow.triggerId,
    name: "Custom trigger",
    description: null,
    fields: [],
    outputs: [],
  };

  const nodes: WorkflowNode[] = [
    {
      id: TRIGGER_NODE_ID,
      type: "workflowNode",
      position: { x: 0, y: 0 },
      data: { kind: "trigger", app: triggerApp, item: triggerItem },
    },
  ];
  const edges: WorkflowEdge[] = [];

  const sortedActions = [...(workflow.actions ?? [])].sort(
    (a, b) => (a.sortingOrder ?? 0) - (b.sortingOrder ?? 0)
  );

  let previousId = TRIGGER_NODE_ID;
  sortedActions.forEach((action, i) => {
    const nodeId = `action-${action.id}`;
    const app = unknownApp(action.appName, action.appName);
    const item: AppAction = {
      id: action.id,
      name: action.name,
      description: null,
      handler: action.type,
      fields: [],
      outputs: [],
    };

    nodes.push({
      id: nodeId,
      type: "workflowNode",
      position: { x: 0, y: (i + 1) * ROW_HEIGHT },
      data: { kind: "action", app, item },
    });
    edges.push({
      id: `edge-${previousId}-${nodeId}`,
      source: previousId,
      target: nodeId,
      type: "workflowEdge",
    });
    previousId = nodeId;
  });

  return { nodes, edges };
}
