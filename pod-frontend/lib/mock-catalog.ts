import type { App } from "@/lib/types";

// Placeholder catalog for local UI work. pod-webhooks only exposes
// POST /apps (create) today -- there's no GET /apps yet, so the app
// gallery and the workflow builder's picker render from this until
// that endpoint exists. Swap for a real fetch once it does.
export const APP_CATALOG: App[] = [
  {
    id: "app_gmail",
    name: "Gmail",
    triggers: [
      { id: "trg_gmail_new_email", name: "New Email", appName: "Gmail" },
      { id: "trg_gmail_new_attachment", name: "New Attachment", appName: "Gmail" },
      { id: "trg_gmail_new_label", name: "New Labeled Email", appName: "Gmail" },
    ],
    actions: [
      { id: "act_gmail_send", name: "Send Email", type: "action", appName: "Gmail" },
      { id: "act_gmail_draft", name: "Create Draft", type: "action", appName: "Gmail" },
    ],
  },
  {
    id: "app_slack",
    name: "Slack",
    triggers: [
      { id: "trg_slack_new_message", name: "New Message in Channel", appName: "Slack" },
      { id: "trg_slack_new_mention", name: "New Mention", appName: "Slack" },
    ],
    actions: [
      { id: "act_slack_post", name: "Post Message", type: "action", appName: "Slack" },
      { id: "act_slack_dm", name: "Send Direct Message", type: "action", appName: "Slack" },
    ],
  },
  {
    id: "app_sheets",
    name: "Google Sheets",
    triggers: [
      { id: "trg_sheets_new_row", name: "New Row", appName: "Google Sheets" },
      { id: "trg_sheets_updated_row", name: "Updated Row", appName: "Google Sheets" },
    ],
    actions: [
      { id: "act_sheets_add_row", name: "Add Row", type: "action", appName: "Google Sheets" },
      { id: "act_sheets_update_row", name: "Update Row", type: "action", appName: "Google Sheets" },
    ],
  },
  {
    id: "app_github",
    name: "GitHub",
    triggers: [
      { id: "trg_github_new_issue", name: "New Issue", appName: "GitHub" },
      { id: "trg_github_new_pr", name: "New Pull Request", appName: "GitHub" },
    ],
    actions: [
      { id: "act_github_create_issue", name: "Create Issue", type: "action", appName: "GitHub" },
      { id: "act_github_comment", name: "Create Comment", type: "action", appName: "GitHub" },
    ],
  },
  {
    id: "app_notion",
    name: "Notion",
    triggers: [
      { id: "trg_notion_new_page", name: "New Page", appName: "Notion" },
      { id: "trg_notion_updated_db", name: "Updated Database Item", appName: "Notion" },
    ],
    actions: [
      { id: "act_notion_create_page", name: "Create Page", type: "action", appName: "Notion" },
      { id: "act_notion_update_page", name: "Update Page", type: "action", appName: "Notion" },
    ],
  },
  {
    id: "app_stripe",
    name: "Stripe",
    triggers: [
      { id: "trg_stripe_new_payment", name: "New Payment", appName: "Stripe" },
      { id: "trg_stripe_new_customer", name: "New Customer", appName: "Stripe" },
    ],
    actions: [
      { id: "act_stripe_create_invoice", name: "Create Invoice", type: "action", appName: "Stripe" },
      { id: "act_stripe_refund", name: "Refund Payment", type: "action", appName: "Stripe" },
    ],
  },
  {
    id: "app_discord",
    name: "Discord",
    triggers: [
      { id: "trg_discord_new_message", name: "New Message", appName: "Discord" },
    ],
    actions: [
      { id: "act_discord_post", name: "Send Channel Message", type: "action", appName: "Discord" },
    ],
  },
  {
    id: "app_trello",
    name: "Trello",
    triggers: [
      { id: "trg_trello_new_card", name: "New Card", appName: "Trello" },
      { id: "trg_trello_card_moved", name: "Card Moved to List", appName: "Trello" },
    ],
    actions: [
      { id: "act_trello_create_card", name: "Create Card", type: "action", appName: "Trello" },
      { id: "act_trello_move_card", name: "Move Card", type: "action", appName: "Trello" },
    ],
  },
];

export function findAppTrigger(triggerId: string) {
  for (const app of APP_CATALOG) {
    const trigger = app.triggers.find((t) => t.id === triggerId);
    if (trigger) return { app, trigger };
  }
  return null;
}

export function findAppAction(actionId: string) {
  for (const app of APP_CATALOG) {
    const action = app.actions.find((a) => a.id === actionId);
    if (action) return { app, action };
  }
  return null;
}
