import { cn } from "@/lib/utils";
import { initials } from "@/lib/utils";

export function Avatar({
  label,
  className,
}: {
  label: string;
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex size-9 shrink-0 items-center justify-center rounded-full bg-lemon text-xs font-black text-black",
        className
      )}
    >
      {initials(label)}
    </div>
  );
}
