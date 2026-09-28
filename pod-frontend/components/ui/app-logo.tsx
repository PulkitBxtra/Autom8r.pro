import { Globe, Webhook } from "lucide-react";
import {
  siDiscord,
  siGithub,
  siGmail,
  siGooglesheets,
  siNotion,
  siStripe,
  siTrello,
  type SimpleIcon,
} from "simple-icons";
import { cn } from "@/lib/utils";

// Brand marks from simple-icons (single-path, drawn in the brand colour), keyed by catalog app id.
const ICONS: Record<string, SimpleIcon> = {
  app_github: siGithub,
  app_notion: siNotion,
  app_stripe: siStripe,
  app_discord: siDiscord,
  app_trello: siTrello,
  app_gmail: siGmail,
  app_sheets: siGooglesheets,
};

// App logo on a white tile, so every brand colour (including black ones like GitHub and Notion)
// reads on the dark UI. Falls back to the app's initial for apps without a logo.
export function AppLogo({
  appId,
  name,
  className,
}: {
  appId?: string | null;
  name: string;
  className?: string;
}) {
  const tile = "flex size-9 shrink-0 items-center justify-center rounded-lg";
  const icon = appId ? ICONS[appId] : undefined;

  if (appId === "app_slack") {
    return (
      <div className={cn(tile, "bg-white", className)} aria-label={name} role="img">
        <SlackMark />
      </div>
    );
  }
  if (icon) {
    return (
      <div className={cn(tile, "bg-white", className)} aria-label={name} role="img">
        <svg viewBox="0 0 24 24" className="size-[55%]" fill={`#${icon.hex}`} aria-hidden>
          <path d={icon.path} />
        </svg>
      </div>
    );
  }
  // Built-in apps: an icon on a neutral tile.
  const BuiltIn = appId === "app_http" ? Globe : appId === "app_webhook" ? Webhook : null;
  if (BuiltIn) {
    return (
      <div className={cn(tile, "bg-white/10 text-text", className)} aria-label={name} role="img">
        <BuiltIn className="size-[55%]" />
      </div>
    );
  }
  return (
    <div className={cn(tile, "bg-lemon text-sm font-black text-black", className)} aria-label={name} role="img">
      {name[0]}
    </div>
  );
}

// Slack's four-colour mark (Slack asked simple-icons to remove theirs).
function SlackMark() {
  return (
    <svg viewBox="0 0 54 54" className="size-[55%]" aria-hidden>
      <path
        fill="#36C5F0"
        d="M19.712.133a5.381 5.381 0 0 0-5.376 5.387 5.381 5.381 0 0 0 5.376 5.386h5.376V5.52A5.381 5.381 0 0 0 19.712.133m0 14.365H5.376A5.381 5.381 0 0 0 0 19.884a5.381 5.381 0 0 0 5.376 5.387h14.336a5.381 5.381 0 0 0 5.376-5.387 5.381 5.381 0 0 0-5.376-5.386"
      />
      <path
        fill="#2EB67D"
        d="M53.76 19.884a5.381 5.381 0 0 0-5.376-5.386 5.381 5.381 0 0 0-5.376 5.386v5.387h5.376a5.381 5.381 0 0 0 5.376-5.387m-14.336 0V5.52A5.381 5.381 0 0 0 34.048.133a5.381 5.381 0 0 0-5.376 5.387v14.364a5.381 5.381 0 0 0 5.376 5.387 5.381 5.381 0 0 0 5.376-5.387"
      />
      <path
        fill="#ECB22E"
        d="M34.048 54a5.381 5.381 0 0 0 5.376-5.387 5.381 5.381 0 0 0-5.376-5.386h-5.376v5.386A5.381 5.381 0 0 0 34.048 54m0-14.365h14.336a5.381 5.381 0 0 0 5.376-5.386 5.381 5.381 0 0 0-5.376-5.387H34.048a5.381 5.381 0 0 0-5.376 5.387 5.381 5.381 0 0 0 5.376 5.386"
      />
      <path
        fill="#E01E5A"
        d="M0 34.249a5.381 5.381 0 0 0 5.376 5.386 5.381 5.381 0 0 0 5.376-5.386v-5.387H5.376A5.381 5.381 0 0 0 0 34.249m14.336 0v14.364A5.381 5.381 0 0 0 19.712 54a5.381 5.381 0 0 0 5.376-5.387V34.249a5.381 5.381 0 0 0-5.376-5.387 5.381 5.381 0 0 0-5.376 5.387"
      />
    </svg>
  );
}
