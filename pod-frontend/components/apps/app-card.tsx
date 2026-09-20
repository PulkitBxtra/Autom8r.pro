import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import type { App } from "@/lib/types";

export function AppCard({ app }: { app: App }) {
  return (
    <Card className="p-5">
      <div className="flex items-center gap-3">
        <div className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-lemon text-base font-black text-black">
          {app.name[0]}
        </div>
        <div className="min-w-0">
          <h3 className="truncate text-base font-bold">{app.name}</h3>
          <p className="text-xs text-text-muted">
            {app.triggers.length} triggers · {app.actions.length} actions
          </p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-1.5">
        {app.triggers.slice(0, 2).map((trigger) => (
          <Badge key={trigger.id} tone="neutral">
            {trigger.name}
          </Badge>
        ))}
        {app.actions.slice(0, 2).map((action) => (
          <Badge key={action.id} tone="lemon">
            {action.name}
          </Badge>
        ))}
      </div>
    </Card>
  );
}
