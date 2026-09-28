"use client";

import { use } from "react";
import { useRouter } from "next/navigation";
import { Plug } from "lucide-react";
import { CredentialsForm } from "@/components/connections/connection-form";
import { ConnectionPage } from "@/components/connections/connection-page";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { useConnections } from "@/hooks/use-connections";

export default function ConnectAppPage({ params }: { params: Promise<{ appId: string }> }) {
  const { appId } = use(params);
  const router = useRouter();
  const { connectors, loading, error } = useConnections();
  const app = connectors.find((c) => c.appId === decodeURIComponent(appId));

  return (
    <ConnectionPage
      title={app ? `Connect ${app.name}` : "New connection"}
      back={{ href: "/connections/new", label: "All apps" }}
      loading={loading}
      error={error}
    >
      {app ? (
        <Card className="p-6">
          <CredentialsForm app={app} reconnect={null} onSaved={() => router.push("/connections")} />
        </Card>
      ) : (
        <EmptyState icon={Plug} title="Unknown app" description="This app can't be connected." />
      )}
    </ConnectionPage>
  );
}
