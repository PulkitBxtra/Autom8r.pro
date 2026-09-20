"use client";

import { X } from "lucide-react";
import { useEffect } from "react";
import { cn } from "@/lib/utils";

export function Dialog({
  open,
  onClose,
  title,
  className,
  children,
}: {
  open: boolean;
  onClose: () => void;
  title?: string;
  className?: string;
  children: React.ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", onKey);
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = "";
    };
  }, [open, onClose]);

  if (!open) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto p-4 pt-16 sm:pt-24">
      <div
        className="fixed inset-0 bg-black/80 backdrop-blur-sm"
        onClick={onClose}
      />
      <div
        className={cn(
          "relative w-full max-w-lg rounded-2xl border border-border-strong bg-surface-raised p-6 shadow-2xl",
          className
        )}
      >
        {title && (
          <div className="mb-5 flex items-center justify-between">
            <h2 className="text-lg font-bold">{title}</h2>
            <button
              onClick={onClose}
              className="rounded-full p-1.5 text-text-muted transition-colors hover:bg-white/10 hover:text-text"
              aria-label="Close"
            >
              <X className="size-4" />
            </button>
          </div>
        )}
        {children}
      </div>
    </div>
  );
}
