"use client";

import { useState } from "react";
import { Check, Copy } from "lucide-react";
import { webhookUrl } from "@/lib/api/workflows";

// The saved workflow a step belongs to: its id, its saved version (null until the first save)
// and whether the editor holds changes not saved yet.
export type SavedWorkflow = { id: string | null; version: number | null; unsaved?: boolean };

// A Webhook trigger's address, shown once the workflow has a saved version: that's what a call
// runs, so before the first save there's nothing to call yet.
export function WebhookUrl({ workflow }: { workflow: SavedWorkflow | null | undefined }) {
  const [copied, setCopied] = useState<string | null>(null);
  const saved = workflow?.id && workflow.version != null;

  async function copy(what: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(what);
      setTimeout(() => setCopied(null), 1500);
    } catch {
      // Clipboard blocked: the text is selectable, so it can be copied by hand.
    }
  }

  return (
    <div data-webhook-url>
      <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-text-muted">Webhook URL</p>
      {!saved ? (
        <p className="rounded-xl border border-dashed border-border-strong px-4 py-3 text-xs text-text-muted">
          Save the workflow to get its URL.
        </p>
      ) : (
        (() => {
          const url = webhookUrl(workflow.id!);
          const curl = `curl -X POST ${url} -H 'Content-Type: application/json' -d '{"hello": "world"}'`;
          return (
            <div className="space-y-2">
              <div className="flex items-center gap-2 rounded-xl border border-border-strong bg-surface-sunken py-1.5 pl-3.5 pr-1.5">
                <code className="min-w-0 flex-1 truncate font-mono text-xs select-all" title={url}>
                  {url}
                </code>
                <button
                  type="button"
                  onClick={() => copy("url", url)}
                  aria-label="Copy the webhook URL"
                  className="flex size-8 shrink-0 items-center justify-center rounded-lg text-text-muted transition-colors hover:bg-white/10 hover:text-text"
                >
                  {copied === "url" ? <Check className="size-4 text-lemon" /> : <Copy className="size-4" />}
                </button>
              </div>
              <p className="text-xs text-text-muted">
                Each call starts a run of the saved version (v{workflow.version}). Send a{" "}
                <span className="font-semibold text-text">POST</span> with a JSON body, or open the URL in a browser
                (<span className="font-semibold text-text">GET</span>) with query parameters like{" "}
                <code className="font-mono text-[11px]">?hello=world</code>. Either becomes the trigger&apos;s data, e.g.{" "}
                <code className="font-mono text-[11px]">{"{{trigger.body.hello}}"}</code>.
              </p>
              {workflow.unsaved && (
                <p className="text-xs text-amber-400">Your unsaved changes aren&apos;t live until you save.</p>
              )}
              <button
                type="button"
                onClick={() => copy("curl", curl)}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-lemon hover:underline"
              >
                {copied === "curl" ? <Check className="size-3.5" /> : <Copy className="size-3.5" />}
                {copied === "curl" ? "Copied" : "Copy a test command (curl)"}
              </button>
            </div>
          );
        })()
      )}
    </div>
  );
}
