import { ArrowRight, Mail, MessageSquare, Table2, Zap } from "lucide-react";
import { Button } from "@/components/ui/button";

const CHAIN = [
  { icon: Mail, label: "New Email", sub: "Gmail", tone: "trigger" as const },
  { icon: Table2, label: "Add Row", sub: "Google Sheets", tone: "action" as const },
  { icon: MessageSquare, label: "Post Message", sub: "Slack", tone: "action" as const },
];

export function Hero() {
  return (
    <section className="relative overflow-hidden bg-grid">
      <div className="pointer-events-none absolute -top-40 left-1/2 h-96 w-[56rem] -translate-x-1/2 rounded-full bg-lemon/20 blur-[120px]" />

      <div className="relative mx-auto max-w-6xl px-4 pb-20 pt-20 sm:px-6 sm:pb-28 sm:pt-28">
        <div className="mx-auto max-w-3xl text-center">
          <div className="mx-auto mb-6 inline-flex items-center gap-2 rounded-full border border-border-strong bg-surface-raised px-4 py-1.5 text-xs font-semibold uppercase tracking-wide text-text-muted">
            <Zap className="size-3.5 text-lemon" />
            Now in early access
          </div>

          <h1 className="text-balance text-5xl font-black leading-[1.05] tracking-tight sm:text-7xl">
            Automate the busywork.
            <br />
            <span className="text-lemon">Ship the real work.</span>
          </h1>

          <p className="mx-auto mt-6 max-w-xl text-balance text-lg text-text-muted">
            Connect your apps, wire up a trigger, chain your actions. Autom8r
            runs the workflow so you don&apos;t have to click through it
            yourself.
          </p>

          <div className="mt-9 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button href="/signup" size="lg">
              Start building free
              <ArrowRight className="size-4" />
            </Button>
            <Button href="#how-it-works" variant="outline" size="lg">
              See how it works
            </Button>
          </div>
        </div>

        <div className="mx-auto mt-16 max-w-3xl rounded-3xl border border-border bg-surface-raised p-4 shadow-[0_0_120px_-20px_rgba(234,255,60,0.15)] sm:p-8">
          <div className="flex flex-col items-stretch gap-3 sm:flex-row sm:items-center">
            {CHAIN.map((step, i) => (
              <div key={step.label} className="flex flex-1 items-center gap-3">
                <div className="flex flex-1 items-center gap-3 rounded-2xl border border-border-strong bg-surface-sunken p-4">
                  <div
                    className={
                      step.tone === "trigger"
                        ? "flex size-10 shrink-0 items-center justify-center rounded-xl bg-lemon text-black"
                        : "flex size-10 shrink-0 items-center justify-center rounded-xl bg-white/10 text-white"
                    }
                  >
                    <step.icon className="size-5" />
                  </div>
                  <div className="min-w-0 text-left">
                    <p className="truncate text-sm font-bold">{step.label}</p>
                    <p className="truncate text-xs text-text-muted">{step.sub}</p>
                  </div>
                </div>
                {i < CHAIN.length - 1 && (
                  <ArrowRight className="hidden size-4 shrink-0 text-text-faint sm:block" />
                )}
              </div>
            ))}
          </div>
        </div>
      </div>
    </section>
  );
}
