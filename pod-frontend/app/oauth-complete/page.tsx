import { OAuthComplete } from "./oauth-complete";

// pod-connector's /oauth/callback redirects the sign-in popup here with the outcome.
export default async function OAuthCompletePage({
  searchParams,
}: {
  searchParams: Promise<{ [key: string]: string | string[] | undefined }>;
}) {
  const params = await searchParams;
  const first = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v) ?? null;
  return (
    <OAuthComplete
      status={first(params.status) === "success" ? "success" : "error"}
      connectionId={first(params.connectionId)}
      appId={first(params.appId)}
      message={first(params.message)}
    />
  );
}
