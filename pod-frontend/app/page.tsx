import { MarketingNav } from "@/components/marketing/nav";
import { Hero } from "@/components/marketing/hero";
import { FeatureGrid } from "@/components/marketing/feature-grid";
import { HowItWorks } from "@/components/marketing/how-it-works";
import { AppMarquee } from "@/components/marketing/app-marquee";
import { Cta } from "@/components/marketing/cta";
import { MarketingFooter } from "@/components/marketing/footer";

export default function Home() {
  return (
    <>
      <MarketingNav />
      <main>
        <Hero />
        <FeatureGrid />
        <HowItWorks />
        <AppMarquee />
        <Cta />
      </main>
      <MarketingFooter />
    </>
  );
}
