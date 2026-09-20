import { GitBranch, History, Plug, ShieldCheck, Timer, Webhook } from "lucide-react";

const FEATURES = [
  {
    icon: Webhook,
    title: "Instant triggers",
    description:
      "React to real events the moment they happen, via webhooks Autom8r manages for you.",
  },
  {
    icon: GitBranch,
    title: "Multi-step actions",
    description:
      "Chain as many actions as a workflow needs, in the exact order you set them up.",
  },
  {
    icon: Plug,
    title: "Growing app library",
    description:
      "Connect the tools your team already runs on, with more integrations shipping regularly.",
  },
  {
    icon: History,
    title: "Full run history",
    description:
      "Every execution is logged, so you can see exactly what ran and when.",
  },
  {
    icon: Timer,
    title: "Reliable delivery",
    description:
      "Built on an outbox pattern under the hood, so triggered runs don't get dropped.",
  },
  {
    icon: ShieldCheck,
    title: "Your data, scoped to you",
    description: "Workflows are tied to your account from the moment you create them.",
  },
];

export function FeatureGrid() {
  return (
    <section id="features" className="border-t border-border bg-surface py-24">
      <div className="mx-auto max-w-6xl px-4 sm:px-6">
        <div className="mx-auto max-w-2xl text-center">
          <p className="text-xs font-bold uppercase tracking-widest text-lemon">
            Why teams switch
          </p>
          <h2 className="mt-3 text-4xl font-black tracking-tight sm:text-5xl">
            Everything a workflow needs
          </h2>
          <p className="mt-4 text-text-muted">
            No more tab-hopping between apps to move information around by
            hand.
          </p>
        </div>

        <div className="mt-14 grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {FEATURES.map((feature) => (
            <div
              key={feature.title}
              className="group rounded-2xl border border-border bg-surface-raised p-6 transition-colors hover:border-lemon/40"
            >
              <div className="flex size-11 items-center justify-center rounded-xl bg-lemon/10 text-lemon transition-colors group-hover:bg-lemon group-hover:text-black">
                <feature.icon className="size-5" />
              </div>
              <h3 className="mt-5 text-lg font-bold">{feature.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-text-muted">
                {feature.description}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
