"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";
import { Sidebar } from "@/components/layout/sidebar";
import { FullPageSpinner } from "@/components/ui/spinner";

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
    <div className="flex min-h-screen bg-surface">
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">{children}</div>
    </div>
  );
}
