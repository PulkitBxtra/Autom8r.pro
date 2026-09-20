import type { Edge, Node } from "@xyflow/react";
import type { App, AppAction, AppTrigger, Workflow } from "@/lib/types";
import { findAppTrigger } from "@/lib/mock-catalog";

export type GraphNodeData = {
  kind: "trigger" | "action";
  app?: App;
  item?: AppTrigger | AppAction;
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

// Backend today only accepts a flat, ordered action list (Workflow.actions +
// Action.sortingOrder) -- no edges. This walks the graph breadth-first from
// the trigger to produce that order, and flags when the graph actually has
// branching/merging so the caller can warn the user it'll be flattened.
export function flattenGraph(nodes: WorkflowNode[], edges: WorkflowEdge[]) {
  const trigger = nodes.find((n) => n.id === TRIGGER_NODE_ID);
  const outEdges = new Map<string, string[]>();
  const inDegree = new Map<string, number>();

  for (const edge of edges) {
    outEdges.set(edge.source, [...(outEdges.get(edge.source) ?? []), edge.target]);
    inDegree.set(edge.target, (inDegree.get(edge.target) ?? 0) + 1);
  }

  const hasBranching =
    [...outEdges.values()].some((targets) => targets.length > 1) ||
    [...inDegree.values()].some((count) => count > 1);

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

  return { trigger, orderedActionNodes, hasBranching };
}

// The backend only stores a flat, ordered action list -- so a workflow
// loaded from the API always renders as a straight chain today. Once
// pod-webhooks persists edges, this can read real branches instead.
export function buildGraphFromWorkflow(workflow: Workflow): {
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
