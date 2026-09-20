import { Topbar } from "@/components/layout/topbar";
import { AppCard } from "@/components/apps/app-card";
import { APP_CATALOG } from "@/lib/mock-catalog";

export default function AppsPage() {
  return (
    <>
      <Topbar title="Apps" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-6xl">
          <p className="mb-6 text-sm text-text-muted">
            {APP_CATALOG.length} apps available to connect
          </p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {APP_CATALOG.map((app) => (
              <AppCard key={app.id} app={app} />
            ))}
          </div>
        </div>
      </div>
    </>
  );
}
