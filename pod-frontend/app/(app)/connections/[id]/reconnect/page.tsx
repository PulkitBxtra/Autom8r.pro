"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { Plug } from "lucide-react";
import { CredentialsForm } from "@/components/connections/connection-form";
import { ConnectionPage } from "@/components/connections/connection-page";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { useConnections } from "@/hooks/use-connections";

// Replaces a connection's credentials in place, so workflows using it keep working.
export default function ReconnectPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const { connections, connectors, loading, error } = useConnections();
  const connection = connections.find((c) => c.id === id);
  const app = connection ? connectors.find((c) => c.appId === connection.appId) : undefined;

  return (
    <ConnectionPage
      title={connection ? `Reconnect ${connection.appName}${connection.label ? ` · ${connection.label}` : ""}` : "Reconnect"}
      back={{ href: "/connections", label: "Connections" }}
      loading={loading}
      error={error}
    >
      {connection && app ? (
        <Card className="p-6">
          {connection.lastError && (
            <p className="mb-5 rounded-lg border border-red-500/30 bg-red-500/5 px-3 py-2.5 text-sm text-red-300">
              {connection.lastError}
            </p>
          )}
          <CredentialsForm app={app} reconnect={connection} onSaved={() => router.push("/connections")} />
        </Card>
      ) : (
        <EmptyState icon={Plug} title="Connection not found" description="It may have been deleted." />
      )}
    </ConnectionPage>
  );
}
