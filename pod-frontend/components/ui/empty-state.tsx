import type { LucideIcon } from "lucide-react";

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
}: {
  icon: LucideIcon;
  title: string;
  description?: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-4 rounded-2xl border border-dashed border-border-strong px-6 py-20 text-center">
      <div className="flex size-12 items-center justify-center rounded-full bg-lemon/10">
        <Icon className="size-5 text-lemon" />
      </div>
      <div className="space-y-1.5">
        <p className="text-base font-semibold text-text">{title}</p>
        {description && (
          <p className="mx-auto max-w-sm text-sm text-text-muted">{description}</p>
        )}
      </div>
      {action}
    </div>
  );
}
