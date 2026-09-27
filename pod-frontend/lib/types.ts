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

// A run is PENDING until pod-processor picks it up, RUNNING while its steps
// execute, then SUCCEEDED or FAILED.
export type RunStatus = "PENDING" | "RUNNING" | "SUCCEEDED" | "FAILED";

export type StepStatus =
  | "PENDING"
  | "READY"
  | "RUNNING"
  | "RETRY_WAIT"
  | "SUCCEEDED"
  | "FAILED"
  | "SKIPPED"
  | "CANCELLED";

// Mirrors pod-backend's RunService.RunSummary.
export type RunSummary = {
  id: string;
  workflowId: string;
  workflowVersionId: string | null;
  version: number | null;
  status: RunStatus;
  startTimestamp: number | null;
  endTimestamp: number | null;
  error: string | null;
};

export type StepDetail = {
  id: string;
  nodeId: string;
  status: StepStatus;
  attempt: number;
  input: Record<string, unknown> | null;
  output: Record<string, unknown> | null;
  error: string | null;
  startedAt: number | null;
  endedAt: number | null;
  nextAttemptAt: number | null;
};

// graph is the version this run executed, which may be older than the
// workflow's current graph.
export type RunDetail = {
  run: RunSummary;
  triggerBody: unknown;
  graph: WorkflowGraph | null;
  steps: StepDetail[];
};

export type AuthUser = {
  id: string;
  email: string;
};
