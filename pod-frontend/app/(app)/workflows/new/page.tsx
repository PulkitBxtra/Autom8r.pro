import { Topbar } from "@/components/layout/topbar";
import { WorkflowBuilder } from "@/components/workflows/workflow-builder";

export default function NewWorkflowPage() {
  return (
    <>
      <Topbar title="New workflow" />
      <div className="flex-1 overflow-hidden">
        <WorkflowBuilder />
      </div>
    </>
  );
}
