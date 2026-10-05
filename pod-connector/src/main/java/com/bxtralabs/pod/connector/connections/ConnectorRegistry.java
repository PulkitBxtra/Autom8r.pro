package com.bxtralabs.pod.connector.connections;

import com.bxtralabs.pod.connector.connections.Connector.CredentialField;
import com.bxtralabs.pod.connector.connections.Connector.TokenAuth;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.bxtralabs.pod.connector.connections.ProviderHttp.string;

// Every app users can connect, and how. Each token check calls the provider's cheapest
// "who am I" endpoint, which both proves the token works and names the account for the list.
// appIds match the app catalog. Provider base URLs are configurable (connectors.<app>.api-base)
// only so tests can point them at a local mock; the defaults are the real APIs.
@Component
public class ConnectorRegistry {

    // Base URLs for each provider's API. trelloOauth: Trello's API for OAuth 2.0 access tokens,
    // which it serves from trello.com rather than api.trello.com.
    public record Endpoints(String github, String slack, String notion, String stripe, String discord, String trello,
                            String trelloOauth, String googleUserinfo) {

        public Endpoints(String github, String slack, String notion, String stripe, String discord, String trello,
                         String trelloOauth) {
            this(github, slack, notion, stripe, discord, trello, trelloOauth, "https://openidconnect.googleapis.com/v1/userinfo");
        }
    }

    // Each Google app's sign-in asks only for what its steps and triggers use: Gmail to send and
    // draft email (gmail.compose) and, for its triggers, to read mail (gmail.readonly, a "restricted"
    // scope: fine while the Google app is in testing, a security review before it's public); Sheets
    // to read and write spreadsheets. openid + email name the account.
    public static final String GMAIL_SCOPES = "openid email https://www.googleapis.com/auth/gmail.compose "
            + "https://www.googleapis.com/auth/gmail.readonly";
    public static final String SHEETS_SCOPES = "openid email https://www.googleapis.com/auth/spreadsheets";

    public static final String SLACK_SIGNING_SECRET = "signingSecret";
    public static final String TRELLO_API_SECRET = "apiSecret";

    private final Map<String, Connector> connectors = new LinkedHashMap<>();

    @Autowired
    public ConnectorRegistry(ProviderHttp http,
                             @Value("${connectors.github.api-base:https://api.github.com}") String github,
                             @Value("${connectors.slack.api-base:https://slack.com/api}") String slack,
                             @Value("${connectors.notion.api-base:https://api.notion.com/v1}") String notion,
                             @Value("${connectors.stripe.api-base:https://api.stripe.com/v1}") String stripe,
                             @Value("${connectors.discord.api-base:https://discord.com/api/v10}") String discord,
                             @Value("${connectors.trello.api-base:https://api.trello.com/1}") String trello,
                             @Value("${connectors.trello.oauth-api-base:https://trello.com/1}") String trelloOauth,
                             @Value("${connectors.google.userinfo-url:https://openidconnect.googleapis.com/v1/userinfo}") String googleUserinfo) {
        this(defaults(http, new Endpoints(github, slack, notion, stripe, discord, trello, trelloOauth, googleUserinfo)));
    }

    // For tests: a registry with specific connectors.
    public ConnectorRegistry(List<Connector> connectors) {
        connectors.forEach(c -> this.connectors.put(c.appId(), c));
    }

    public List<Connector> all() {
        return List.copyOf(connectors.values());
    }

    public Optional<Connector> find(String appId) {
        return Optional.ofNullable(appId == null ? null : connectors.get(appId));
    }

    static List<Connector> defaults(ProviderHttp http, Endpoints api) {
        List<Connector> list = new ArrayList<>();

        list.add(new Connector("app_github", "GitHub", "Repositories, issues and pull requests",
                new TokenAuth(List.of(secret("token", "Personal access token", "ghp_… or github_pat_…",
                        "GitHub → Settings → Developer settings → Personal access tokens. Needs repo access for issues and comments.")),
                        "https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens",
                        creds -> {
                            Map<String, Object> user = http.get("GitHub", api.github() + "/user", Map.of(
                                    "Authorization", "Bearer " + creds.get("token"),
                                    "Accept", "application/vnd.github+json"));
                            return "@" + string(user, "login");
                        }),
                "github"));

        list.add(new Connector("app_slack", "Slack", "Post messages and follow channels",
                new TokenAuth(List.of(secret("token", "Bot token", "xoxb-…",
                                "Your Slack app → OAuth & Permissions → Bot User OAuth Token."),
                        // Slack signs its events with the app's signing secret; triggers on a
                        // connection to the user's own app check events against it.
                        new CredentialField(SLACK_SIGNING_SECRET, "Signing secret", true, false, "",
                                "Only needed for Slack triggers: your Slack app → Basic Information → Signing Secret.")),
                        "https://api.slack.com/authentication/token-types#bot",
                        creds -> {
                            Map<String, Object> res = http.post("Slack", api.slack() + "/auth.test",
                                    Map.of("Authorization", "Bearer " + creds.get("token")), null);
                            // Slack answers 200 even for bad tokens; ok=false carries the reason.
                            if (!Boolean.TRUE.equals(res.get("ok"))) {
                                throw new ConnectionVerificationException("Slack rejected this token (" + res.get("error") + ")");
                            }
                            String user = string(res, "user");
                            return string(res, "team") + (user != null ? " · @" + user : "");
                        }),
                "slack"));

        list.add(new Connector("app_notion", "Notion", "Pages and databases",
                new TokenAuth(List.of(secret("token", "Internal integration secret", "ntn_…",
                        "notion.so/my-integrations → your integration → Internal Integration Secret. Then share pages with the integration.")),
                        "https://developers.notion.com/docs/authorization#internal-integration-auth-flow-set-up",
                        creds -> {
                            Map<String, Object> me = http.get("Notion", api.notion() + "/users/me", Map.of(
                                    "Authorization", "Bearer " + creds.get("token"),
                                    "Notion-Version", "2022-06-28"));
                            String workspace = string(me, "bot", "workspace_name");
                            return string(me, "name") + (workspace != null ? " · " + workspace : "");
                        }),
                "notion"));

        list.add(new Connector("app_stripe", "Stripe", "Payments, customers and invoices",
                new TokenAuth(List.of(secret("apiKey", "Secret or restricted key", "sk_test_… / rk_live_…",
                        "Stripe Dashboard → Developers → API keys. Prefer a restricted key with only the access you need.")),
                        "https://docs.stripe.com/keys",
                        creds -> {
                            // /balance works for any key (including restricted ones with balance read) and says which mode it is.
                            Map<String, Object> balance = http.get("Stripe", api.stripe() + "/balance",
                                    Map.of("Authorization", "Bearer " + creds.get("apiKey")));
                            return "Stripe (" + (Boolean.TRUE.equals(balance.get("livemode")) ? "live" : "test") + " mode)";
                        }),
                null));

        list.add(new Connector("app_discord", "Discord", "Send messages to channels",
                new TokenAuth(List.of(secret("token", "Bot token", "",
                        "Discord Developer Portal → your application → Bot → Reset Token.")),
                        "https://discord.com/developers/docs/topics/oauth2#bots",
                        creds -> string(http.get("Discord", api.discord() + "/users/@me",
                                Map.of("Authorization", "Bot " + creds.get("token"))), "username")),
                null));

        list.add(new Connector("app_trello", "Trello", "Boards, lists and cards",
                new TokenAuth(List.of(
                        new CredentialField("apiKey", "API key", false, true, "",
                                "trello.com/power-ups/admin → your Power-Up → API key."),
                        secret("token", "Token", "", "Generate one from the same page (the \"Token\" link next to the API key)."),
                        new CredentialField(TRELLO_API_SECRET, "API secret", true, false, "",
                                "Only for Trello triggers: the Secret shown under the same API key. Trello signs trigger events with it.")),
                        "https://developer.atlassian.com/cloud/trello/guides/rest-api/api-introduction/",
                        creds -> {
                            // A key and token from the form; or, signing in with Trello, only the
                            // OAuth 2.0 access token (passed as "token").
                            Map<String, Object> me = creds.get("apiKey") == null
                                    ? http.get("Trello", api.trelloOauth() + "/members/me?fields=fullName,username",
                                            Map.of("Authorization", "Bearer " + creds.get("token")))
                                    : http.get("Trello", api.trello() + "/members/me?fields=fullName,username",
                                            Map.of("Authorization", "OAuth oauth_consumer_key=\"" + creds.get("apiKey")
                                                    + "\", oauth_token=\"" + creds.get("token") + "\""));
                            String fullName = string(me, "fullName");
                            return fullName != null && !fullName.isBlank() ? fullName : "@" + string(me, "username");
                        }),
                "trello"));

        // Google only offers OAuth for these scopes, so no token form: "Connect with Google", named
        // by the account's email.
        Connector.TokenVerifier googleAccount = creds -> string(http.get("Google", api.googleUserinfo(),
                Map.of("Authorization", "Bearer " + creds.get("token"))), "email");
        list.add(new Connector("app_gmail", "Gmail", "Send email and drafts", null, "google", GMAIL_SCOPES, googleAccount));
        list.add(new Connector("app_sheets", "Google Sheets", "Read and write spreadsheet rows", null, "google", SHEETS_SCOPES,
                googleAccount));

        // Any API behind a header credential, for http_request steps. Nothing to check it against,
        // so the user names it.
        list.add(new Connector("app_http", "HTTP / Custom API", "A header credential for any HTTP API",
                new TokenAuth(List.of(
                        new CredentialField("name", "Connection name", false, true, "Acme API (prod)",
                                "How this connection appears in lists."),
                        new CredentialField("headerName", "Header", false, true, "Authorization",
                                "The header the credential is sent in."),
                        secret("headerValue", "Header value", "Bearer …",
                                "The token or key, exactly as the header should carry it.")),
                        null,
                        creds -> creds.get("name")),
                null));

        return list;
    }

    private static CredentialField secret(String key, String label, String placeholder, String help) {
        return new CredentialField(key, label, true, true, placeholder, help);
    }
}
