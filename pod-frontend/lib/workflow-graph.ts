import type { Edge, Node } from "@xyflow/react";
import type {
  App,
  AppAction,
  AppTrigger,
  GraphNode,
  Workflow,
  WorkflowGraph,
} from "@/lib/types";
import { findAppAction, findAppTrigger } from "@/lib/mock-catalog";

export type GraphNodeData = {
  kind: "trigger" | "action";
  app?: App;
  item?: AppTrigger | AppAction;
  // Carried through untouched so a load -> save round-trip never drops config.
  parameters?: Record<string, unknown>;
};

export type WorkflowNode = Node<GraphNodeData, "workflowNode">;
export type PlaceholderNodeData = { parentId: string };
export type PlaceholderNode = Node<PlaceholderNodeData, "placeholderNode">;
export type CanvasNode = WorkflowNode | PlaceholderNode;
export type WorkflowEdge = Edge;

export const TRIGGER_NODE_ID = "trigger";
export const CHILD_X_SPACING = 300;
export const CHILD_Y_SPACING = 170;
// Node card is w-64 (16rem); the placeholder add-step button is size-8 (2rem).
export const NODE_WIDTH = 256;
export const PLACEHOLDER_WIDTH = 32;

export function createActionNodeId() {
  return `action-${crypto.randomUUID()}`;
}

// Where the "add a step" affordance for a node's next (or next-branch) child
// belongs -- reused by the quick-add flow and by the placeholder node/edge
// pair so a placeholder always sits exactly where the real node will land.
export function nextChildSlot(node: WorkflowNode, edges: WorkflowEdge[]) {
  const siblingCount = edges.filter((e) => e.source === node.id).length;
  return {
    x: node.position.x + siblingCount * CHILD_X_SPACING,
    y: node.position.y + CHILD_Y_SPACING,
  };
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

// Breadth-first order from the trigger, used only to number steps in the UI
// ("Step 3"). Execution order comes from the graph's edges, not from this.
export function orderSteps(nodes: WorkflowNode[], edges: WorkflowEdge[]) {
  const trigger = nodes.find((n) => n.id === TRIGGER_NODE_ID);
  const outEdges = new Map<string, string[]>();

  for (const edge of edges) {
    outEdges.set(edge.source, [...(outEdges.get(edge.source) ?? []), edge.target]);
  }

  const visited = new Set<string>();
  const orderedIds: string[] = [];
  const queue = trigger ? [...(outEdges.get(trigger.id) ?? [])] : [];

  while (queue.length > 0) {
    const id = queue.shift()!;
    if (visited.has(id)) continue;
    visited.add(id);
    orderedIds.push(id);
    queue.push(...(outEdges.get(id) ?? []));
  }

  const byId = new Map(nodes.map((n) => [n.id, n]));
  const orderedActionNodes = orderedIds
    .map((id) => byId.get(id))
    .filter((n): n is WorkflowNode => !!n && n.data.kind === "action");

  return { trigger, orderedActionNodes };
}

function isConfigured(node: WorkflowNode) {
  return !!node.data.app && !!node.data.item;
}

// Converts the canvas into the graph pod-backend stores. Steps added with +
// but never given an app are dropped when nothing hangs off them (there's no
// delete in the UI yet, so one stray click shouldn't block saving). An
// unconfigured step in the middle of a path can't be dropped without
// breaking it, so that returns an error naming the step instead.
export function toWorkflowGraph(
  nodes: WorkflowNode[],
  edges: WorkflowEdge[]
): { graph: WorkflowGraph } | { error: string; nodeId: string } {
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
    return {
      nodeId: unconfigured.id,
      error:
        unconfigured.data.kind === "trigger"
          ? "Choose a trigger before saving"
          : "A step in the middle of this workflow has no app selected",
    };
  }

  return {
    graph: {
      nodes: keptNodes.map(
        (n): GraphNode => ({
          id: n.id,
          kind: n.data.kind,
          appName: n.data.app!.name,
          itemId: n.data.item!.id,
          name: n.data.item!.name,
          type: "type" in n.data.item! ? n.data.item.type : null,
          parameters: n.data.parameters ?? {},
          position: { x: n.position.x, y: n.position.y },
        })
      ),
      edges: keptEdges.map((e) => ({
        from: e.source,
        to: e.target,
        condition: (e.data?.condition as string | undefined) ?? null,
      })),
    },
  };
}

// Catalog lookup with a fallback built from what the graph stored, so a step
// whose app later disappears from the catalog still renders with its name.
function resolveNodeItem(node: GraphNode): { app: App; item: AppTrigger | AppAction } {
  const fallbackApp: App = { id: node.appName, name: node.appName, actions: [], triggers: [] };

  if (node.kind === "trigger") {
    const found = findAppTrigger(node.itemId);
    return found
      ? { app: found.app, item: found.trigger }
      : { app: fallbackApp, item: { id: node.itemId, name: node.name ?? node.itemId, appName: node.appName } };
  }

  const found = findAppAction(node.itemId);
  return found
    ? { app: found.app, item: found.action }
    : {
        app: fallbackApp,
        item: { id: node.itemId, name: node.name ?? node.itemId, type: node.type ?? "action", appName: node.appName },
      };
}

// Workflows saved since versioning carry their real graph (branches, joins,
// layout). Older ones only have the flat legacy action list, which renders as
// a straight chain.
export function buildGraphFromWorkflow(workflow: Workflow): {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
} {
  if (workflow.graph) {
    return {
      nodes: workflow.graph.nodes.map((n, i) => ({
        id: n.id,
        type: "workflowNode",
        position: n.position ?? { x: 0, y: i * CHILD_Y_SPACING },
        data: { kind: n.kind, ...resolveNodeItem(n), parameters: n.parameters ?? {} },
      })),
      edges: workflow.graph.edges.map((e) => ({
        id: `edge-${e.from}-${e.to}`,
        source: e.from,
        target: e.to,
        type: "workflowEdge",
        ...(e.condition ? { data: { condition: e.condition } } : {}),
      })),
    };
  }

  return buildLegacyGraph(workflow);
}

export function countActions(workflow: Workflow) {
  if (workflow.graph) {
    return workflow.graph.nodes.filter((n) => n.kind === "action").length;
  }
  return workflow.actions?.length ?? 0;
}

function buildLegacyGraph(workflow: Workflow): {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
} {
  const found = findAppTrigger(workflow.triggerId);
  const triggerApp: App = found?.app ?? {
    id: "unknown-app",
    name: "Custom trigger",
    actions: [],
    triggers: [],
  };
  const triggerItem: AppTrigger = found?.trigger ?? {
    id: workflow.triggerId,
    name: "Custom trigger",
    appName: triggerApp.name,
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
    const app: App = {
      id: action.appName,
      name: action.appName,
      actions: [],
      triggers: [],
    };
    const item: AppAction = {
      id: action.id,
      name: action.name,
      type: action.type,
      appName: action.appName,
    };

    nodes.push({
      id: nodeId,
      type: "workflowNode",
      position: { x: 0, y: (i + 1) * 170 },
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
