const BACKEND_URL = process.env.NEXT_PUBLIC_BACKEND_URL ?? "http://localhost:8083";
const WEBHOOKS_URL = process.env.NEXT_PUBLIC_WEBHOOKS_URL ?? "http://localhost:8080";

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function extractErrorMessage(data: unknown): string | null {
  if (typeof data === "string") return data;
  if (data && typeof data === "object") {
    const record = data as Record<string, unknown>;
    if (typeof record.error === "string") return record.error;
    if (typeof record.message === "string") return record.message;
    // Bean validation errors come back as { field: "message" } with no envelope.
    const values = Object.values(record).filter((v) => typeof v === "string");
    if (values.length > 0) return values.join(", ");
  }
  return null;
}

type Base = "backend" | "webhooks";

async function request<T>(
  base: Base,
  path: string,
  options: RequestInit & { token?: string | null } = {}
): Promise<T> {
  const { token, headers, ...rest } = options;
  const baseUrl = base === "backend" ? BACKEND_URL : WEBHOOKS_URL;

  const res = await fetch(`${baseUrl}${path}`, {
    ...rest,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
  });

  const text = await res.text();
  // A couple of endpoints (createWorkflow, triggerWorkflow) return a raw
  // id string with a String return type, not JSON -- fall back to the
  // plain text when it doesn't parse.
  let data: unknown = null;
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = text;
    }
  }

  if (!res.ok) {
    throw new ApiError(res.status, extractErrorMessage(data) || res.statusText || "Request failed");
  }

  return data as T;
}

export const backend = {
  get: <T>(path: string, token?: string | null) =>
    request<T>("backend", path, { method: "GET", token }),
  post: <T>(path: string, body?: unknown, token?: string | null) =>
    request<T>("backend", path, {
      method: "POST",
      body: body ? JSON.stringify(body) : undefined,
      token,
    }),
};

export const webhooks = {
  get: <T>(path: string, token?: string | null) =>
    request<T>("webhooks", path, { method: "GET", token }),
  post: <T>(path: string, body?: unknown, token?: string | null) =>
    request<T>("webhooks", path, {
      method: "POST",
      body: body ? JSON.stringify(body) : undefined,
      token,
    }),
};
