import { cn } from "@/lib/utils";
import type { LabelHTMLAttributes } from "react";

export function Label({
  className,
  ...props
}: LabelHTMLAttributes<HTMLLabelElement>) {
  return (
    <label
      className={cn(
        "block text-xs font-semibold uppercase tracking-wide text-text-muted mb-2",
        className
      )}
      {...props}
    />
  );
}
