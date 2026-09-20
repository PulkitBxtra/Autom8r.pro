import { cn } from "@/lib/utils";
import type { InputHTMLAttributes } from "react";

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      className={cn(
        "h-11 w-full rounded-lg border border-border-strong bg-surface-sunken px-3.5 text-sm text-text placeholder:text-text-faint outline-none transition-colors focus:border-lemon",
        className
      )}
      {...props}
    />
  );
}
