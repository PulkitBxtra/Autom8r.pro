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

export type Workflow = {
  id: string;
  name: string;
  triggerId: string;
  userId: string;
  actions: Action[];
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
