import { connector } from "./client";
import type { AppConnection, ConnectorInfo } from "@/lib/types";

export function listConnectors(token: string) {
  return connector.get<ConnectorInfo[]>("/connectors", token);
}

export function listConnections(token: string, appId?: string) {
  const query = appId ? `?appId=${encodeURIComponent(appId)}` : "";
  return connector.get<AppConnection[]>(`/connections${query}`, token);
}

// pod-connector checks the credentials with the provider before saving; a 400
// carries the provider's reason (e.g. "GitHub rejected these credentials").
export function createConnection(appId: string, credentials: Record<string, string>, token: string) {
  return connector.post<AppConnection>("/connections", { appId, credentials }, token);
}

// Same connection id afterwards, so workflows using it keep working.
export function reconnectConnection(id: string, credentials: Record<string, string>, token: string) {
  return connector.put<AppConnection>(`/connection/${id}`, { credentials }, token);
}

export function deleteConnection(id: string, token: string) {
  return connector.delete<null>(`/connection/${id}`, token);
}
