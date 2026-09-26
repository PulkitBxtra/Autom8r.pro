export type AppAction = {
  id: string;
  name: string;
  type: string;
  appName: string;
};

export type AppTrigger = {
  id: string;
  name: string;
  appName: string;
};

export type App = {
  id: string;
  name: string;
  actions: AppAction[];
  triggers: AppTrigger[];
};

export type Action = {
  id: string;
  name: string;
  type: string;
  appName: string;
  sortingOrder?: number;
  parameters?: Record<string, unknown>;
};

// Mirrors pod-backend's WorkflowGraph: the DAG saved as one immutable version per save.
export type GraphNode = {
  id: string;
  kind: "trigger" | "action";
  appName: string;
  // Catalog AppTrigger/AppAction id.
  itemId: string;
  name?: string | null;
  type?: string | null;
  parameters?: Record<string, unknown> | null;
  position?: { x: number; y: number } | null;
};

export type GraphEdge = {
  from: string;
  to: string;
  // Not evaluated by the engine yet; null means always taken.
  condition?: string | null;
};

export type WorkflowGraph = {
  nodes: GraphNode[];
  edges: GraphEdge[];
};

export type Workflow = {
  id: string;
  name: string;
  triggerId: string;
  userId: string;
  currentVersionId?: string | null;
  version?: number | null;
  // Null for workflows saved before versioning; those only have the legacy actions list.
  graph?: WorkflowGraph | null;
  actions?: Action[] | null;
};

export type ExecutionRun = {
  id: string;
  workflowId: string;
  status: "PENDING" | "RUNNING" | "SUCCESS" | "FAILED" | string;
  startTimestamp: number;
  endTimestamp: number | null;
  metadata?: Record<string, unknown>;
};

export type AuthUser = {
  id: string;
  email: string;
};
