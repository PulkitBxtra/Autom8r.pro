import { AppLogo } from "@/components/ui/app-logo";

// Marketing copy for the landing page, not the live catalog (that's GET /apps once signed in).
const APPS = [
  { id: "app_gmail", name: "Gmail" },
  { id: "app_slack", name: "Slack" },
  { id: "app_sheets", name: "Google Sheets" },
  { id: "app_github", name: "GitHub" },
  { id: "app_notion", name: "Notion" },
  { id: "app_stripe", name: "Stripe" },
  { id: "app_discord", name: "Discord" },
  { id: "app_trello", name: "Trello" },
  { id: "app_http", name: "Any HTTP API" },
];

export function AppMarquee() {
  const row = [...APPS, ...APPS];

  return (
    <section id="apps" className="border-t border-border bg-surface py-24">
      <div className="mx-auto max-w-6xl px-4 text-center sm:px-6">
        <p className="text-xs font-bold uppercase tracking-widest text-lemon">
          Connects with
        </p>
        <h2 className="mt-3 text-4xl font-black tracking-tight sm:text-5xl">
          The apps your team runs on
        </h2>
      </div>

      <div className="relative mt-14 overflow-hidden">
        <div className="pointer-events-none absolute inset-y-0 left-0 z-10 w-24 bg-gradient-to-r from-surface to-transparent" />
        <div className="pointer-events-none absolute inset-y-0 right-0 z-10 w-24 bg-gradient-to-l from-surface to-transparent" />

        <div className="flex w-max animate-marquee gap-4">
          {row.map((app, i) => (
            <div
              key={`${app.id}-${i}`}
              className="flex items-center gap-3 rounded-2xl border border-border bg-surface-raised px-6 py-4"
            >
              <AppLogo appId={app.id} name={app.name} />
              <span className="whitespace-nowrap text-sm font-semibold">
                {app.name}
              </span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
