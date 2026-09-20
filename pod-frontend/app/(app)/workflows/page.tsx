"use client";

import { Plus, Workflow as WorkflowIcon } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";
import { WorkflowCard } from "@/components/workflows/workflow-card";
import { useWorkflows } from "@/hooks/use-workflows";

export default function WorkflowsPage() {
  const { workflows, loading, error } = useWorkflows();

  return (
    <>
      <Topbar title="Workflows" />
      <div className="flex-1 overflow-y-auto p-6">
        <div className="mx-auto max-w-6xl">
          <div className="mb-6 flex items-center justify-between">
            <p className="text-sm text-text-muted">
              {workflows.length} workflow{workflows.length === 1 ? "" : "s"}
            </p>
            <Button href="/workflows/new" size="sm">
              <Plus className="size-4" />
              New workflow
            </Button>
          </div>

          {loading && <FullPageSpinner />}

          {!loading && error && (
            <EmptyState
              icon={WorkflowIcon}
              title="Couldn't load workflows"
              description={error}
            />
          )}

          {!loading && !error && workflows.length === 0 && (
            <EmptyState
              icon={WorkflowIcon}
              title="No workflows yet"
              description="Create your first workflow to start automating."
              action={
                <Button href="/workflows/new" size="sm">
                  <Plus className="size-4" />
                  New workflow
                </Button>
              }
            />
          )}

          {!loading && !error && workflows.length > 0 && (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {workflows.map((workflow) => (
                <WorkflowCard key={workflow.id} workflow={workflow} />
              ))}
            </div>
          )}
        </div>
      </div>
    </>
  );
}
