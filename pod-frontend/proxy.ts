import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

// One deployment serves two hosts: SITE_HOST (e.g. autom8r.pro) shows only the landing page,
// APP_HOST (e.g. app.autom8r.pro) only the app. Anything else on the site host moves to the app
// host with the same path, and the app host's "/" goes to the dashboard. With either variable
// unset (local dev, preview deployments) every page is served on whatever host was used.
const SITE_HOST = process.env.SITE_HOST?.toLowerCase();
const APP_HOST = process.env.APP_HOST?.toLowerCase();

export function proxy(request: NextRequest) {
  if (!SITE_HOST || !APP_HOST) return NextResponse.next();

  const host = (request.headers.get("host") ?? "").split(":")[0].toLowerCase();
  const { pathname, search } = request.nextUrl;

  if ((host === SITE_HOST || host === `www.${SITE_HOST}`) && pathname !== "/") {
    return NextResponse.redirect(`https://${APP_HOST}${pathname}${search}`, 308);
  }
  if (host === `www.${SITE_HOST}`) {
    return NextResponse.redirect(`https://${SITE_HOST}/${search}`, 308);
  }
  if (host === APP_HOST && pathname === "/") {
    return NextResponse.redirect(`https://${APP_HOST}/dashboard${search}`, 307);
  }
  return NextResponse.next();
}

export const config = {
  // Pages only: Next's own assets and files with an extension (images, favicon) are served on both hosts.
  matcher: ["/((?!_next/static|_next/image|.*\\.[A-Za-z0-9]+$).*)"],
};
