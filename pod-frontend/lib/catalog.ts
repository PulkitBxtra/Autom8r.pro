import type { App, AppAction, AppTrigger } from "@/lib/types";

// The catalog with lookups by trigger/action id.
export type Catalog = {
  apps: App[];
  findTrigger: (id: string) => { app: App; trigger: AppTrigger } | null;
  findAction: (id: string) => { app: App; action: AppAction } | null;
};

export function buildCatalog(apps: App[]): Catalog {
  const triggers = new Map<string, { app: App; trigger: AppTrigger }>();
  const actions = new Map<string, { app: App; action: AppAction }>();
  for (const app of apps) {
    for (const trigger of app.triggers) triggers.set(trigger.id, { app, trigger });
    for (const action of app.actions) actions.set(action.id, { app, action });
  }
  return {
    apps,
    findTrigger: (id) => triggers.get(id) ?? null,
    findAction: (id) => actions.get(id) ?? null,
  };
}

export const EMPTY_CATALOG = buildCatalog([]);
