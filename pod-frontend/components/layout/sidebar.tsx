"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutGrid, Workflow, Plug, Settings } from "lucide-react";
import { cn } from "@/lib/utils";
import { Logo } from "./logo";

const NAV = [
  { href: "/dashboard", label: "Dashboard", icon: LayoutGrid },
  { href: "/workflows", label: "Workflows", icon: Workflow },
  { href: "/apps", label: "Apps", icon: Plug },
];

export function Sidebar() {
  const pathname = usePathname();

  return (
    <aside className="hidden w-64 shrink-0 flex-col border-r border-border bg-surface px-4 py-6 lg:flex">
      <Link href="/dashboard" className="mb-8 px-2">
        <Logo />
      </Link>

      <nav className="flex flex-1 flex-col gap-1">
        {NAV.map(({ href, label, icon: Icon }) => {
          const active = pathname === href || pathname.startsWith(`${href}/`);
          return (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium transition-colors",
                active
                  ? "bg-lemon text-black"
                  : "text-text-muted hover:bg-white/5 hover:text-text"
              )}
            >
              <Icon className="size-4.5" />
              {label}
            </Link>
          );
        })}
      </nav>

      <Link
        href="/settings"
        className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-sm font-medium text-text-muted transition-colors hover:bg-white/5 hover:text-text"
      >
        <Settings className="size-4.5" />
        Settings
      </Link>
    </aside>
  );
}
