// The app catalog, from pod-backend's GET /apps (resources/catalog/apps.json there).
// Trigger/action ids are stable: saved workflow graphs refer to them.

// How a setting is edited and what its value is: text/textarea strings (may hold {{...}}
// data from earlier steps), number, boolean, select (one of options), keyvalue (object of
// strings), json (any JSON value).
export type FieldType =
  | "text"
  | "textarea"
  | "number"
  | "boolean"
  | "select"
  | "keyvalue"
  | "json"
  // Logic steps: a set of conditions, or named paths each with its conditions (lib/logic.ts).
  | "conditions"
  | "paths";

export type CatalogField = {
  key: string;
  label: string;
  type: FieldType;
  required: boolean;
  placeholder: string | null;
  help: string | null;
  options: { value: string; label: string }[] | null;
  defaultValue: unknown;
  // Hidden once saved: pod-backend sends SECRET_MASK instead. On keyvalue, only values of
  // sensitive-looking names (see isSensitiveKey).
  secret: boolean;
};

export type AppTrigger = {
  id: string;
  name: string;
  description: string | null;
  fields: CatalogField[];
};

export type AppAction = {
  id: string;
  name: string;
  description: string | null;
  // pod-processor handler that runs it; saved as the step's type.
  handler: string;
  fields: CatalogField[];
};

export type App = {
  id: string;
  name: string;
  description: string | null;
  // Whether its steps act through one of the user's connections: required (GitHub...),
  // optional (HTTP: most APIs need no credential) or none (Webhook, Logic).
  connection: "required" | "optional" | "none";
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
  // Catalog app id (app_github...). Missing in graphs saved before steps used connections.
  appId?: string | null;
  // The pod-connector connection this step acts through; null if none is chosen.
  connectionId?: string | null;
};

export type GraphEdge = {
  from: string;
  to: string;
  // Older-style edge condition (still evaluated by the engine; the editor no longer sets it).
  condition?: string | null;
  // Which output of a Logic step the edge leaves from (a path id, "otherwise", "if"/"else").
  sourceHandle?: string | null;
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
  // Switched on: its app trigger listens for events (app-trigger workflows only).
  active?: boolean;
};

// Whether an active workflow's app trigger is registered with the app (from pod-connector).
export type TriggerStatus = {
  status: "ACTIVE" | "ERROR";
  error: string | null;
  lastEventAt: number | null;
  appId: string;
  triggerId: string;
  // Where the user must point their own app's events (Slack with a bot token), else null.
  eventsUrl: string | null;
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

// One credential input on an app's connection form. secret inputs are masked
// and never shown again after saving.
export type CredentialField = {
  key: string;
  label: string;
  secret: boolean;
  required: boolean;
  placeholder: string | null;
  help: string | null;
};

// An app that can be connected, and how (mirrors pod-connector's ConnectorView).
// tokenFields is null for OAuth-only apps. oauthAvailable: the app supports OAuth, so the
// user can sign in with their own OAuth app; platformOAuthAvailable: the server's app too.
export type ConnectorInfo = {
  appId: string;
  name: string;
  description: string;
  tokenFields: CredentialField[] | null;
  docsUrl: string | null;
  oauthProvider: string | null;
  oauthProviderName: string | null;
  oauthAvailable: boolean;
  platformOAuthAvailable: boolean;
  // Where to create an OAuth app with the provider, and the callback URL to register in it.
  oauthSetupUrl: string | null;
  callbackUrl: string | null;
};

export type ConnectionStatus = "ACTIVE" | "NEEDS_REAUTH";

// A saved connection. Never carries the credentials themselves.
export type AppConnection = {
  id: string;
  appId: string;
  appName: string;
  label: string | null;
  authType: "TOKEN" | "OAUTH";
  status: ConnectionStatus;
  lastError: string | null;
  // The user's own OAuth app it signed in through; null = the server's app (or a token).
  oauthClientId: string | null;
  createdAt: number | null;
  updatedAt: number | null;
  lastUsedAt: number | null;
};

// One of the user's own OAuth apps (mirrors pod-connector's OAuthClientView). The client
// secret is never sent back.
export type OAuthClient = {
  id: string;
  provider: string;
  providerName: string;
  name: string;
  clientId: string;
  connectionCount: number;
  createdAt: number | null;
  updatedAt: number | null;
};

export type AuthUser = {
  id: string;
  email: string;
};
