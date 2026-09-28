"use client";

import Link from "next/link";
import { AlertTriangle, ArrowLeft } from "lucide-react";
import { Topbar } from "@/components/layout/topbar";
import { EmptyState } from "@/components/ui/empty-state";
import { FullPageSpinner } from "@/components/ui/spinner";

// Shared frame for the connection pages: title, a back link, and the load/error states.
export function ConnectionPage({
  title,
  back,
  loading,
  error,
  width = "max-w-2xl",
  children,
}: {
  title: string;
  back: { href: string; label: string };
  loading: boolean;
  error: { status: number; message: string } | null;
  width?: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <Topbar title={title} />
      <div className="flex-1 overflow-y-auto p-6">
        <div className={`mx-auto ${width}`}>
          <Link
            href={back.href}
            className="mb-5 inline-flex items-center gap-1.5 text-sm text-text-muted transition-colors hover:text-text"
          >
            <ArrowLeft className="size-4" />
            {back.label}
          </Link>
          {loading ? (
            <FullPageSpinner />
          ) : error ? (
            <EmptyState
              icon={AlertTriangle}
              title={error.status === 503 ? "Connections aren't set up on the server" : "Couldn't load connections"}
              description={
                error.status === 503
                  ? "pod-connector is running without CONNECTIONS_ENCRYPTION_KEY, so it can't store credentials."
                  : error.message
              }
            />
          ) : (
            children
          )}
        </div>
      </div>
    </>
  );
}
