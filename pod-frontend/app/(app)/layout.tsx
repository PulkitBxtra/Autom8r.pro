"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { Sidebar } from "@/components/layout/sidebar";
import { FullPageSpinner } from "@/components/ui/spinner";
import { CatalogProvider } from "@/lib/catalog-context";

export default function AppShellLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { status } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (status === "unauthenticated") router.replace("/login");
  }, [status, router]);

  if (status !== "authenticated") {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface">
        <FullPageSpinner />
      </div>
    );
  }

  return (
    <CatalogProvider>
      {/* Window-high: long pages scroll inside the content column, and the workflow editor's
          canvas and step drawer fit the window, with the drawer scrolling on its own. */}
      <div className="flex h-dvh overflow-hidden bg-surface">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col overflow-y-auto">{children}</div>
      </div>
    </CatalogProvider>
  );
}
