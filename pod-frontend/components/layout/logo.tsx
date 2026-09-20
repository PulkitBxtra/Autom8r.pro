import { cn } from "@/lib/utils";

export function LogoMark({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 32 32"
      className={cn("size-7", className)}
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <rect width="32" height="32" rx="8" fill="currentColor" className="text-lemon" />
      <path
        d="M8 20L13 11L16.5 17L20 11L24 20"
        stroke="black"
        strokeWidth="2.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function Logo({ className }: { className?: string }) {
  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <LogoMark />
      <span className="text-lg font-black tracking-tight">Autom8r</span>
    </div>
  );
}
