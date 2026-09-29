import { connector } from "./client";
import type { AppConnection, ConnectorInfo, FieldOptions, OAuthClient } from "@/lib/types";

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

// Choices for a step setting from one of the user's accounts; q narrows them.
export function listOptions(connectionId: string, source: string, q: string, token: string) {
  const query = q ? `?q=${encodeURIComponent(q)}` : "";
  return connector.get<FieldOptions>(`/connections/${connectionId}/options/${source}${query}`, token);
}

export function deleteConnection(id: string, token: string) {
  return connector.delete<null>(`/connection/${id}`, token);
}

// Returns the provider's sign-in URL to open in a popup. connectionId: reconnect that one.
// oauthClientId: sign in through that OAuth app of the user's; omitted = the server's app.
export function startOAuth(
  appId: string,
  token: string,
  options: { connectionId?: string; oauthClientId?: string | null } = {}
) {
  return connector.post<{ authorizeUrl: string }>(
    `/oauth/${encodeURIComponent(appId)}/start`,
    { connectionId: options.connectionId ?? null, oauthClientId: options.oauthClientId ?? null },
    token
  );
}

// The user's own OAuth apps. provider narrows to one (e.g. "github").
export function listOAuthClients(token: string, provider?: string) {
  const query = provider ? `?provider=${encodeURIComponent(provider)}` : "";
  return connector.get<OAuthClient[]>(`/oauth-clients${query}`, token);
}

export function createOAuthClient(
  input: { provider: string; name?: string; clientId: string; clientSecret: string },
  token: string
) {
  return connector.post<OAuthClient>("/oauth-clients", input, token);
}

// Rename and/or replace the secret; an empty secret keeps the current one.
export function updateOAuthClient(id: string, input: { name?: string; clientSecret?: string }, token: string) {
  return connector.put<OAuthClient>(`/oauth-client/${id}`, input, token);
}

// 409 while connections still use it.
export function deleteOAuthClient(id: string, token: string) {
  return connector.delete<null>(`/oauth-client/${id}`, token);
}

// What /oauth-complete reports back to the page that opened the sign-in popup.
export type OAuthResult = {
  type: "autom8r-oauth";
  status: "success" | "error";
  connectionId: string | null;
  appId: string | null;
  message: string | null;
};

// Same-origin channel between the popup and the page. Used alongside window.opener because
// providers often send Cross-Origin-Opener-Policy headers that null out window.opener.
export const OAUTH_CHANNEL = "autom8r-oauth";
