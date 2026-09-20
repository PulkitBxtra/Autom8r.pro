"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { LogOut, Search } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { Avatar } from "@/components/ui/avatar";

export function Topbar({ title }: { title?: string }) {
  const { user, logout } = useAuth();
  const router = useRouter();
  const [menuOpen, setMenuOpen] = useState(false);

  return (
    <header className="flex h-16 shrink-0 items-center justify-between gap-4 border-b border-border px-6">
      <div className="min-w-0">
        {title && <h1 className="truncate text-lg font-bold">{title}</h1>}
      </div>

      <div className="flex items-center gap-3">
        <div className="relative hidden sm:block">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-text-faint" />
          <input
            placeholder="Search workflows..."
            className="h-9 w-64 rounded-full border border-border-strong bg-surface-sunken pl-9 pr-3 text-sm outline-none placeholder:text-text-faint focus:border-lemon"
          />
        </div>

        <div className="relative">
          <button
            onClick={() => setMenuOpen((v) => !v)}
            className="flex items-center gap-2 rounded-full transition-opacity hover:opacity-80"
          >
            <Avatar label={user?.email ?? "?"} />
          </button>

          {menuOpen && (
            <>
              <div
                className="fixed inset-0 z-10"
                onClick={() => setMenuOpen(false)}
              />
              <div className="absolute right-0 top-11 z-20 w-56 overflow-hidden rounded-xl border border-border-strong bg-surface-raised shadow-2xl">
                <div className="border-b border-border px-4 py-3">
                  <p className="truncate text-sm font-semibold">{user?.email}</p>
                </div>
                <button
                  onClick={async () => {
                    await logout();
                    router.push("/login");
                  }}
                  className="flex w-full items-center gap-2 px-4 py-3 text-sm text-text-muted transition-colors hover:bg-white/5 hover:text-text"
                >
                  <LogOut className="size-4" />
                  Log out
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
