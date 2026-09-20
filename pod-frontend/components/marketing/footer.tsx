import Link from "next/link";
import { Logo } from "@/components/layout/logo";

export function MarketingFooter() {
  return (
    <footer className="border-t border-border bg-surface py-12">
      <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-6 px-4 sm:flex-row sm:px-6">
        <Logo />
        <p className="text-sm text-text-faint">
          © {new Date().getFullYear()} Autom8r. All rights reserved.
        </p>
        <div className="flex gap-6 text-sm text-text-muted">
          <Link href="/login" className="hover:text-text">
            Log in
          </Link>
          <Link href="/signup" className="hover:text-text">
            Sign up
          </Link>
        </div>
      </div>
    </footer>
  );
}
