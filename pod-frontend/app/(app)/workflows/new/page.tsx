import { Topbar } from "@/components/layout/topbar";
import { WorkflowBuilder } from "@/components/workflows/workflow-builder";
import { StepConnectionsProvider } from "@/components/workflows/step-connections";

export default function NewWorkflowPage() {
  return (
    <>
      <Topbar title="New workflow" />
      <div className="flex-1 overflow-hidden">
        <StepConnectionsProvider>
          <WorkflowBuilder />
        </StepConnectionsProvider>
      </div>
    </>
  );
}
