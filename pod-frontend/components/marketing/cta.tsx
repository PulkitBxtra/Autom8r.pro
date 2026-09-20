import { ArrowRight } from "lucide-react";
import { Button } from "@/components/ui/button";

export function Cta() {
  return (
    <section className="border-t border-border bg-surface py-24">
      <div className="mx-auto max-w-4xl rounded-3xl bg-lemon px-6 py-16 text-center sm:px-16">
        <h2 className="text-4xl font-black tracking-tight text-black sm:text-5xl">
          Stop doing this by hand.
        </h2>
        <p className="mx-auto mt-4 max-w-md text-black/70">
          Build your first workflow in minutes, no credit card required.
        </p>
        <Button
          href="/signup"
          size="lg"
          className="mt-8 bg-black text-lemon hover:bg-black/85"
        >
          Get started free
          <ArrowRight className="size-4" />
        </Button>
      </div>
    </section>
  );
}
