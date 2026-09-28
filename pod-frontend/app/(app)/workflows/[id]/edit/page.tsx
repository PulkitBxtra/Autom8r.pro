"use client";

import { use, useEffect, useState } from "react";
import { History } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { WorkflowBuilder } from "@/components/workflows/workflow-builder";
import { StepConnectionsProvider } from "@/components/workflows/step-connections";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";
import { useAuth } from "@/lib/auth-context";
import { useCatalog } from "@/lib/catalog-context";
import { getWorkflow } from "@/lib/api/workflows";
import { ApiError } from "@/lib/api/client";
import type { Workflow } from "@/lib/types";

export default function EditWorkflowPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { token } = useAuth();
  const catalog = useCatalog();
  const [workflow, setWorkflow] = useState<Workflow | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) return;
    getWorkflow(id, token)
      .then(setWorkflow)
      .catch((err) => setError(err instanceof ApiError ? err.message : "Failed to load workflow"));
  }, [id, token]);

  return (
    <>
      <Topbar title={workflow ? `Edit ${workflow.name}` : "Edit workflow"} />
      {error ? (
        <div className="p-6">
          <EmptyState icon={History} title="Couldn't load workflow" description={error} />
        </div>
      ) : !workflow || catalog.loading ? (
        <FullPageSpinner />
      ) : (
        <div className="flex-1 overflow-hidden">
          <StepConnectionsProvider>
            {/* The builder reads the workflow once, so start over if a newer version loads. */}
            <WorkflowBuilder key={workflow.currentVersionId ?? workflow.id} existing={workflow} />
          </StepConnectionsProvider>
        </div>
      )}
    </>
  );
}
