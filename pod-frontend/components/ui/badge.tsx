import { cn } from "@/lib/utils";

type Tone = "neutral" | "lemon" | "success" | "danger" | "pending";

const toneClasses: Record<Tone, string> = {
  neutral: "bg-white/5 text-text-muted border-border-strong",
  lemon: "bg-lemon/10 text-lemon border-lemon/30",
  success: "bg-emerald-500/10 text-emerald-400 border-emerald-500/30",
  danger: "bg-red-500/10 text-red-400 border-red-500/30",
  pending: "bg-amber-500/10 text-amber-400 border-amber-500/30",
};

export function Badge({
  tone = "neutral",
  className,
  ...props
}: React.HTMLAttributes<HTMLSpanElement> & { tone?: Tone }) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide",
        toneClasses[tone],
        className
      )}
      {...props}
    />
  );
}
