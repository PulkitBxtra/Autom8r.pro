import { backend } from "./client";
import type { App } from "@/lib/types";

// Public; no login needed.
export function listApps() {
  return backend.get<App[]>("/apps");
}
