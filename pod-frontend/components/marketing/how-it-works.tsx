const STEPS = [
  {
    number: "01",
    title: "Pick a trigger",
    description: "Choose the app and event that should kick off your workflow.",
  },
  {
    number: "02",
    title: "Add your actions",
    description: "Stack up the steps that should run, in the order you want them.",
  },
  {
    number: "03",
    title: "Turn it on",
    description: "Autom8r listens for the trigger and runs every step for you.",
  },
];

export function HowItWorks() {
  return (
    <section id="how-it-works" className="border-t border-border bg-surface py-24">
      <div className="mx-auto max-w-6xl px-4 sm:px-6">
        <div className="mx-auto max-w-2xl text-center">
          <p className="text-xs font-bold uppercase tracking-widest text-lemon">
            How it works
          </p>
          <h2 className="mt-3 text-4xl font-black tracking-tight sm:text-5xl">
            Three steps to your first automation
          </h2>
        </div>

        <div className="mt-14 grid grid-cols-1 gap-8 sm:grid-cols-3">
          {STEPS.map((step) => (
            <div key={step.number}>
              <span className="text-5xl font-black text-white/10">
                {step.number}
              </span>
              <h3 className="mt-3 text-xl font-bold">{step.title}</h3>
              <p className="mt-2 text-sm leading-relaxed text-text-muted">
                {step.description}
              </p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
