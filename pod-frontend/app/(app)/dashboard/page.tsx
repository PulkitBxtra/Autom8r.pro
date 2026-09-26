"use client";

import { Plug, Plus, Workflow as WorkflowIcon, Zap } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";
import { WorkflowCard } from "@/components/workflows/workflow-card";
import { useAuth } from "@/lib/auth-context";
import { useWorkflows } from "@/hooks/use-workflows";
import { APP_CATALOG } from "@/lib/mock-catalog";
import { countActions } from "@/lib/workflow-graph";

export default function DashboardPage() {
  const { user } = useAuth();
  const { workflows, loading } = useWorkflows();
  const totalActions = workflows.reduce((sum, w) => sum + countActions(w), 0);

  return (
    <>
      <Topbar title="Dashboard" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-6xl">
          <div className="mb-8 flex flex-wrap items-center justify-between gap-4">
            <div>
              <h2 className="text-2xl font-black">
                Welcome back{user?.email ? `, ${user.email.split("@")[0]}` : ""}
              </h2>
              <p className="mt-1 text-sm text-text-muted">
                Here&apos;s what&apos;s running.
              </p>
            </div>
            <Button href="/workflows/new">
              <Plus className="size-4" />
              New workflow
            </Button>
          </div>

          <div className="mb-10 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <Card className="p-5">
              <div className="flex items-center gap-3">
                <div className="flex size-10 items-center justify-center rounded-xl bg-lemon/10 text-lemon">
                  <WorkflowIcon className="size-5" />
                </div>
                <div>
                  <p className="text-2xl font-black">{workflows.length}</p>
                  <p className="text-xs text-text-muted">Workflows</p>
                </div>
              </div>
            </Card>
            <Card className="p-5">
              <div className="flex items-center gap-3">
                <div className="flex size-10 items-center justify-center rounded-xl bg-lemon/10 text-lemon">
                  <Zap className="size-5" />
                </div>
                <div>
                  <p className="text-2xl font-black">{totalActions}</p>
                  <p className="text-xs text-text-muted">Configured actions</p>
                </div>
              </div>
            </Card>
            <Card className="p-5">
              <div className="flex items-center gap-3">
                <div className="flex size-10 items-center justify-center rounded-xl bg-lemon/10 text-lemon">
                  <Plug className="size-5" />
                </div>
                <div>
                  <p className="text-2xl font-black">{APP_CATALOG.length}</p>
                  <p className="text-xs text-text-muted">Apps available</p>
                </div>
              </div>
            </Card>
          </div>

          <div className="mb-4 flex items-center justify-between">
            <h3 className="text-sm font-bold uppercase tracking-wide text-text-muted">
              Recent workflows
            </h3>
            <Button href="/workflows" variant="ghost" size="sm">
              View all
            </Button>
          </div>

          {loading && <FullPageSpinner />}

          {!loading && workflows.length === 0 && (
            <EmptyState
              icon={WorkflowIcon}
              title="No workflows yet"
              description="Create your first workflow to see it here."
              action={
                <Button href="/workflows/new" size="sm">
                  <Plus className="size-4" />
                  New workflow
                </Button>
              }
            />
          )}

          {!loading && workflows.length > 0 && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {workflows.slice(0, 6).map((workflow) => (
                <WorkflowCard key={workflow.id} workflow={workflow} />
              ))}
            </div>
          )}
        </div>
      </div>
    </>
  );
}
