import { Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cn("size-5 animate-spin text-lemon", className)} />;
}

export function FullPageSpinner() {
  return (
    <div className="flex flex-1 items-center justify-center py-24">
      <Spinner />
    </div>
  );
}
