"use client";

import { AppPicker } from "@/components/connections/connection-form";
import { ConnectionPage } from "@/components/connections/connection-page";
import { useConnections } from "@/hooks/use-connections";

export default function NewConnectionPage() {
  const { connectors, loading, error } = useConnections();

  return (
    <ConnectionPage
      title="New connection"
      back={{ href: "/connections", label: "Connections" }}
      loading={loading}
      error={error}
      width="max-w-5xl"
    >
      <p className="mb-5 text-sm text-text-muted">Which app do you want to connect?</p>
      <AppPicker connectors={connectors} />
    </ConnectionPage>
  );
}
