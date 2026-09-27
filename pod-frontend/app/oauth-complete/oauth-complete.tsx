"use client";

import { useEffect } from "react";
import Link from "next/link";
import { CheckCircle2, XCircle } from "lucide-react";
import { OAUTH_CHANNEL, type OAuthResult } from "@/lib/api/connections";

// Tells the Connections page how the sign-in went, then closes the popup. If this page was
// opened some other way (or the popup can't be closed), it shows the result instead.
export function OAuthComplete(props: Omit<OAuthResult, "type">) {
  const { status, message } = props;

  useEffect(() => {
    const result: OAuthResult = { type: "autom8r-oauth", ...props };
    const channel = new BroadcastChannel(OAUTH_CHANNEL);
    channel.postMessage(result);
    channel.close();
    // Only our own origin may receive it.
    window.opener?.postMessage(result, window.location.origin);
    // Give the message a moment to be delivered before closing.
    const timer = setTimeout(() => window.close(), 300);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- report once, on load
  }, []);

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="max-w-sm text-center">
        {status === "success" ? (
          <CheckCircle2 className="mx-auto mb-3 size-10 text-emerald-400" />
        ) : (
          <XCircle className="mx-auto mb-3 size-10 text-red-400" />
        )}
        <h1 className="text-lg font-bold">{status === "success" ? "Connected" : "Couldn't connect"}</h1>
        {message && <p className="mt-2 text-sm text-text-muted">{message}</p>}
        <p className="mt-4 text-xs text-text-faint">You can close this window.</p>
        <Link href="/connections" className="mt-3 inline-block text-xs font-medium text-lemon hover:underline">
          Go to Connections
        </Link>
      </div>
    </div>
  );
}
