"use client";

import { useState } from "react";
import Link from "next/link";
import { Menu, X } from "lucide-react";
import { Logo } from "@/components/layout/logo";
import { Button } from "@/components/ui/button";

const LINKS = [
  { href: "#features", label: "Features" },
  { href: "#how-it-works", label: "How it works" },
  { href: "#apps", label: "Apps" },
];

export function MarketingNav() {
  const [open, setOpen] = useState(false);

  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-surface/85 backdrop-blur-md">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-4 sm:px-6">
        <Link href="/">
          <Logo />
        </Link>

        <nav className="hidden items-center gap-8 md:flex">
          {LINKS.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="text-sm font-medium text-text-muted transition-colors hover:text-text"
            >
              {link.label}
            </a>
          ))}
        </nav>

        <div className="hidden items-center gap-3 md:flex">
          <Button href="/login" variant="ghost" size="sm">
            Log in
          </Button>
          <Button href="/signup" size="sm">
            Get started
          </Button>
        </div>

        <button
          className="p-2 text-text md:hidden"
          onClick={() => setOpen((v) => !v)}
          aria-label="Toggle menu"
        >
          {open ? <X className="size-5" /> : <Menu className="size-5" />}
        </button>
      </div>

      {open && (
        <div className="border-t border-border px-4 py-4 md:hidden">
          <nav className="flex flex-col gap-4">
            {LINKS.map((link) => (
              <a
                key={link.href}
                href={link.href}
                onClick={() => setOpen(false)}
                className="text-sm font-medium text-text-muted"
              >
                {link.label}
              </a>
            ))}
            <div className="mt-2 flex flex-col gap-2">
              <Button href="/login" variant="outline" size="md" className="w-full">
                Log in
              </Button>
              <Button href="/signup" size="md" className="w-full">
                Get started
              </Button>
            </div>
          </nav>
        </div>
      )}
    </header>
  );
}
