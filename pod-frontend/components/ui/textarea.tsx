import { cn } from "@/lib/utils";
import type { TextareaHTMLAttributes } from "react";

export function Textarea({
  className,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={cn(
        "w-full rounded-lg border border-border-strong bg-surface-sunken px-3.5 py-3 text-sm text-text placeholder:text-text-faint outline-none transition-colors focus:border-lemon",
        className
      )}
      {...props}
    />
  );
}
