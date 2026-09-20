import Link from "next/link";
import { ArrowRight, Zap } from "lucide-react";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { findAppTrigger } from "@/lib/mock-catalog";
import type { Workflow } from "@/lib/types";

export function WorkflowCard({ workflow }: { workflow: Workflow }) {
  const trigger = findAppTrigger(workflow.triggerId);
  const actionCount = workflow.actions?.length ?? 0;

  return (
    <Link href={`/workflows/${workflow.id}`}>
      <Card className="group h-full p-5 transition-colors hover:border-lemon/40">
        <div className="flex items-start justify-between gap-3">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-lemon/10 text-lemon">
            <Zap className="size-5" />
          </div>
          <ArrowRight className="size-4 shrink-0 text-text-faint transition-transform group-hover:translate-x-0.5 group-hover:text-lemon" />
        </div>

        <h3 className="mt-4 truncate text-base font-bold">{workflow.name}</h3>
        <p className="mt-1 truncate text-sm text-text-muted">
          {trigger
            ? `${trigger.trigger.name} · ${trigger.app.name}`
            : "Custom trigger"}
        </p>

        <div className="mt-4 flex items-center gap-2">
          <Badge tone="lemon">
            {actionCount} {actionCount === 1 ? "action" : "actions"}
          </Badge>
        </div>
      </Card>
    </Link>
  );
}
