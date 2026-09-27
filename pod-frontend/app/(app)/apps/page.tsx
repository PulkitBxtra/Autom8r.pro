import { redirect } from "next/navigation";

// "Apps" became "Connections"; keep old links and bookmarks working.
export default function AppsPage() {
  redirect("/connections");
}
