package com.bxtralabs.pod.connector.options;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.ConnectionNeedsReauthException;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

// Choices for a step setting, read from the step's account: the Slack channels a bot can see,
// the GitHub repositories a token can reach, the Notion databases and pages shared with an
// integration, the Discord channels a bot can post in, the lists on a member's Trello boards, an
// account's Stripe customers... The catalog names the list on the field (optionsFrom). Each option's value is
// what the step saves (an id, or owner/repo), its label what the user reads.
// Lists are kept for a minute per account, so typing in the search box doesn't call the app on
// every key; Notion is searched by the app itself, since a workspace can hold thousands of pages.
@Service
public class OptionsService {

    public record Option(String value, String label, String hint) {
    }

    // more: there were more matches than returned; typing narrows them.
    public record Options(List<Option> options, boolean more) {
    }

    // The lists, and the app whose accounts they come from.
    static final Map<String, String> SOURCES = Map.of(
            "slack.channels", "app_slack",
            "slack.users", "app_slack",
            "github.repos", "app_github",
            "notion.databases", "app_notion",
            "notion.pages", "app_notion",
            "notion.parents", "app_notion",
            "discord.channels", "app_discord",
            "trello.lists", "app_trello",
            "stripe.customers", "app_stripe");

    static final int LIMIT = 100;
    static final long CACHE_MS = 60_000;
    private static final int MAX_PAGES = 10;

    private final ConnectionRepository connections;
    private final TokenService tokens;
    private final JsonMapper jsonMapper;
    private final String githubApi;
    private final String slackApi;
    private final String notionApi;
    private final String discordApi;
    private final String trelloApi;
    private final String trelloOauthApi;
    private final String stripeApi;
    private final LongSupplier clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long at, List<Option> options) {
    }

    @Autowired
    public OptionsService(ConnectionRepository connections, TokenService tokens, JsonMapper jsonMapper,
                          @Value("${connectors.github.api-base:https://api.github.com}") String githubApi,
                          @Value("${connectors.slack.api-base:https://slack.com/api}") String slackApi,
                          @Value("${connectors.notion.api-base:https://api.notion.com/v1}") String notionApi,
                          @Value("${connectors.discord.api-base:https://discord.com/api/v10}") String discordApi,
                          @Value("${connectors.trello.api-base:https://api.trello.com/1}") String trelloApi,
                          @Value("${connectors.trello.oauth-api-base:https://trello.com/1}") String trelloOauthApi,
                          @Value("${connectors.stripe.api-base:https://api.stripe.com/v1}") String stripeApi) {
        this(connections, tokens, jsonMapper, githubApi, slackApi, notionApi, discordApi, trelloApi, trelloOauthApi,
                stripeApi, System::currentTimeMillis);
    }

    OptionsService(ConnectionRepository connections, TokenService tokens, JsonMapper jsonMapper,
                   String githubApi, String slackApi, String notionApi, String discordApi, String trelloApi,
                   String trelloOauthApi, String stripeApi, LongSupplier clock) {
        this.connections = connections;
        this.tokens = tokens;
        this.jsonMapper = jsonMapper;
        this.githubApi = githubApi.replaceAll("/+$", "");
        this.slackApi = slackApi.replaceAll("/+$", "");
        this.notionApi = notionApi.replaceAll("/+$", "");
        this.discordApi = discordApi.replaceAll("/+$", "");
        this.trelloApi = trelloApi.replaceAll("/+$", "");
        this.trelloOauthApi = trelloOauthApi.replaceAll("/+$", "");
        this.stripeApi = stripeApi.replaceAll("/+$", "");
        this.clock = clock;
    }

    public Options options(String userId, String connectionId, String source, String query) {
        String appId = SOURCES.get(source);
        if (appId == null) {
            throw new NotFoundException("Unknown list: " + source);
        }
        Connection c = connections.findById(connectionId)
                .filter(found -> found.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Connection not found: " + connectionId));
        if (!c.getAppId().equals(appId)) {
            throw new IllegalArgumentException("That account isn't one this list comes from");
        }
        String q = query == null ? "" : query.trim();
        boolean notion = source.startsWith("notion.");
        String key = connectionId + "|" + source + (notion ? "|" + q.toLowerCase(Locale.ROOT) : "");
        long now = clock.getAsLong();
        Cached hit = cache.get(key);
        List<Option> all;
        if (hit != null && now - hit.at() < CACHE_MS) {
            all = hit.options();
        } else {
            all = fetch(c, source, q);
            cache.put(key, new Cached(now, all));
            cache.values().removeIf(old -> now - old.at() >= CACHE_MS);
        }
        String needle = q.toLowerCase(Locale.ROOT);
        List<Option> matching = notion || needle.isEmpty() ? all : all.stream()
                .filter(o -> o.label().toLowerCase(Locale.ROOT).contains(needle)
                        || o.value().toLowerCase(Locale.ROOT).contains(needle)
                        || (o.hint() != null && o.hint().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
        return new Options(matching.stream().limit(LIMIT).toList(), matching.size() > LIMIT);
    }

    private List<Option> fetch(Connection c, String source, String query) {
        Map<String, String> credentials = tokens.getValidCredentials(c.getId());
        String token = credentials.get("access_token") != null ? credentials.get("access_token") : credentials.get("token");
        // Taken after getValidCredentials, which may have just refreshed the token.
        Connection current = connections.findById(c.getId()).orElse(c);
        Account account = new Account(current, token, credentials.get("apiKey"), TokenService.version(current));
        return switch (source) {
            case "slack.channels" -> slackChannels(account);
            case "slack.users" -> slackUsers(account);
            case "github.repos" -> githubRepos(account);
            case "notion.databases" -> notion(account, query, "database");
            case "notion.pages" -> notion(account, query, "page");
            case "notion.parents" -> notion(account, query, null);
            case "discord.channels" -> discordChannels(account);
            case "trello.lists" -> trelloLists(account);
            case "stripe.customers" -> stripeCustomers(account);
            default -> throw new NotFoundException("Unknown list: " + source);
        };
    }

    // apiKey: the key that goes with the token, for apps that need both (Trello).
    private record Account(Connection connection, String token, String apiKey, String version) {
    }

    // ---- Slack ----

    private List<Option> slackChannels(Account a) {
        List<Option> out = new ArrayList<>();
        for (Map<?, ?> ch : slackPages(a, "conversations.list", "channels",
                Map.of("types", "public_channel", "exclude_archived", "true", "limit", "200"))) {
            String members = number(ch.get("num_members"));
            String hint = members + ("1".equals(members) ? " member" : " members");
            if (!Boolean.TRUE.equals(ch.get("is_member"))) hint += " · bot not in it yet";
            out.add(new Option(str(ch.get("id")), "#" + str(ch.get("name")), hint));
        }
        out.sort(Comparator.comparing(Option::label));
        return out;
    }

    private List<Option> slackUsers(Account a) {
        List<Option> out = new ArrayList<>();
        for (Map<?, ?> u : slackPages(a, "users.list", "members", Map.of("limit", "200"))) {
            if (Boolean.TRUE.equals(u.get("deleted")) || Boolean.TRUE.equals(u.get("is_bot")) || "USLACKBOT".equals(u.get("id"))) {
                continue;
            }
            Map<?, ?> profile = u.get("profile") instanceof Map<?, ?> p ? p : Map.of();
            String real = firstNonBlank(str(profile.get("real_name")), str(u.get("real_name")), str(u.get("name")));
            String handle = firstNonBlank(str(profile.get("display_name")), str(u.get("name")));
            out.add(new Option(str(u.get("id")), real, "@" + handle));
        }
        out.sort(Comparator.comparing(o -> o.label().toLowerCase(Locale.ROOT)));
        return out;
    }

    private List<Map<?, ?>> slackPages(Account a, String method, String listKey, Map<String, String> params) {
        List<Map<?, ?>> items = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> form = new LinkedHashMap<>(params);
            if (!cursor.isEmpty()) form.put("cursor", cursor);
            Response r = send(HttpRequest.newBuilder(URI.create(slackApi + "/" + method))
                    .header("Authorization", "Bearer " + a.token())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(form))), "Slack");
            Map<?, ?> body = r.map();
            if (!Boolean.TRUE.equals(body.get("ok"))) {
                String error = str(body.get("error"));
                if (Set.of("invalid_auth", "token_revoked", "account_inactive", "not_authed").contains(error)) {
                    rejected(a, "Slack rejected the token (" + error + ")");
                }
                if ("missing_scope".equals(error)) {
                    throw new OptionsException(422, "This Slack account can't list these (it needs the "
                            + body.get("needed") + " permission). Reconnect it to grant it.");
                }
                throw new OptionsException(502, "Slack couldn't list them (" + error + ")");
            }
            if (body.get(listKey) instanceof List<?> list) {
                list.forEach(x -> {
                    if (x instanceof Map<?, ?> m) items.add(m);
                });
            }
            Object next = body.get("response_metadata") instanceof Map<?, ?> m ? m.get("next_cursor") : null;
            if (next == null || str(next).isEmpty()) break;
            cursor = str(next);
        }
        return items;
    }

    // ---- GitHub ----

    private List<Option> githubRepos(Account a) {
        List<Option> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Response r = send(HttpRequest.newBuilder(URI.create(githubApi + "/user/repos?per_page=100&sort=pushed&page=" + page))
                    .header("Authorization", "Bearer " + a.token())
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET(), "GitHub");
            if (r.status() == 401) {
                rejected(a, "GitHub rejected the token");
            }
            if (r.status() != 200) {
                throw new OptionsException(502, "GitHub couldn't list repositories (HTTP " + r.status() + ")");
            }
            List<?> repos = r.list();
            for (Object x : repos) {
                if (x instanceof Map<?, ?> repo) {
                    String hint = Boolean.TRUE.equals(repo.get("private")) ? "Private" : "Public";
                    if (repo.get("description") != null && !str(repo.get("description")).isBlank()) {
                        hint += " · " + str(repo.get("description"));
                    }
                    out.add(new Option(str(repo.get("full_name")), str(repo.get("full_name")), hint));
                }
            }
            if (repos.size() < 100) break;
        }
        return out;
    }

    // ---- Notion ----

    // kind: "database", "page", or null for both.
    private List<Option> notion(Account a, String query, String kind) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!query.isEmpty()) body.put("query", query);
        if (kind != null) body.put("filter", Map.of("property", "object", "value", kind));
        body.put("sort", Map.of("direction", "descending", "timestamp", "last_edited_time"));
        body.put("page_size", LIMIT);
        Response r = send(HttpRequest.newBuilder(URI.create(notionApi + "/search"))
                .header("Authorization", "Bearer " + a.token())
                .header("Notion-Version", "2022-06-28")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), "Notion");
        if (r.status() == 401) {
            rejected(a, "Notion rejected the token");
        }
        if (r.status() != 200) {
            throw new OptionsException(502, "Notion couldn't search (HTTP " + r.status() + ")");
        }
        List<Option> out = new ArrayList<>();
        if (r.map().get("results") instanceof List<?> results) {
            for (Object x : results) {
                if (x instanceof Map<?, ?> item) {
                    boolean database = "database".equals(item.get("object"));
                    String title = notionTitle(item);
                    out.add(new Option(str(item.get("id")), title.isBlank() ? "Untitled" : title, database ? "Database" : "Page"));
                }
            }
        }
        return out;
    }

    // A database's title, or the text of a page's title property.
    private static String notionTitle(Map<?, ?> item) {
        Object rich = item.get("title");
        if (rich == null && item.get("properties") instanceof Map<?, ?> props) {
            for (Object p : props.values()) {
                if (p instanceof Map<?, ?> prop && "title".equals(prop.get("type"))) {
                    rich = prop.get("title");
                    break;
                }
            }
        }
        StringBuilder text = new StringBuilder();
        if (rich instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> m && m.get("plain_text") != null) text.append(m.get("plain_text"));
            }
        }
        return text.toString().trim();
    }

    // ---- Discord ----

    // Text and announcement channels in every server the bot is in, in the order Discord shows
    // them: channels outside any category first, then each category in its place.
    static final int MAX_DISCORD_SERVERS = 25;

    private List<Option> discordChannels(Account a) {
        List<?> guilds = discord(a, "/users/@me/guilds?limit=200").list();
        if (guilds.isEmpty()) {
            throw new OptionsException(422, "This bot isn't in any Discord server yet. Invite it to one (Discord Developer "
                    + "Portal → your app → OAuth2 → URL Generator, scope bot), then try again.");
        }
        List<Option> out = new ArrayList<>();
        for (Object g : guilds.stream().limit(MAX_DISCORD_SERVERS).toList()) {
            if (!(g instanceof Map<?, ?> guild)) continue;
            List<Map<?, ?>> channels = new ArrayList<>();
            discord(a, "/guilds/" + str(guild.get("id")) + "/channels").list().forEach(c -> {
                if (c instanceof Map<?, ?> m) channels.add(m);
            });
            Map<String, String> categories = new HashMap<>();
            Map<String, Integer> categoryPositions = new HashMap<>();
            channels.stream().filter(c -> number(c.get("type")).equals("4")).forEach(c -> {
                categories.put(str(c.get("id")), str(c.get("name")));
                categoryPositions.put(str(c.get("id")), position(c));
            });
            channels.stream()
                    // 0 text, 5 announcement: the channels messages can be posted in.
                    .filter(c -> Set.of("0", "5").contains(number(c.get("type"))))
                    .sorted(Comparator.comparing((Map<?, ?> c) -> categoryPositions.getOrDefault(str(c.get("parent_id")), -1))
                            .thenComparing(OptionsService::position))
                    .forEach(c -> {
                        String category = categories.get(str(c.get("parent_id")));
                        out.add(new Option(str(c.get("id")), "#" + str(c.get("name")),
                                str(guild.get("name")) + (category == null || category.isBlank() ? "" : " · " + category)));
                    });
        }
        return out;
    }

    private static int position(Map<?, ?> channel) {
        return channel.get("position") instanceof Number n ? n.intValue() : 0;
    }

    private Response discord(Account a, String path) {
        Response r = send(HttpRequest.newBuilder(URI.create(discordApi + path))
                .header("Authorization", "Bot " + a.token())
                .header("User-Agent", "DiscordBot (https://autom8r.pro, 1.0)")
                .GET(), "Discord");
        if (r.status() == 401) {
            rejected(a, "Discord rejected the bot token");
        }
        if (r.status() != 200) {
            throw new OptionsException(502, "Discord couldn't list channels (HTTP " + r.status() + ")");
        }
        return r;
    }

    // ---- Trello ----

    // The open lists on the member's open boards, board by board as Trello orders them. With a key
    // and token from the form, or an access token from signing in with Trello (no key).
    private List<Option> trelloLists(Account a) {
        boolean oauth = a.apiKey() == null;
        Response r = send(HttpRequest.newBuilder(URI.create((oauth ? trelloOauthApi : trelloApi)
                        + "/members/me/boards?filter=open&fields=name&lists=open&list_fields=name,pos"))
                .header("Authorization", oauth ? "Bearer " + a.token()
                        : "OAuth oauth_consumer_key=\"" + a.apiKey() + "\", oauth_token=\"" + a.token() + "\"")
                .header("Accept", "application/json")
                .GET(), "Trello");
        if (r.status() == 401) {
            rejected(a, "Trello rejected the key and token");
        }
        if (r.status() != 200) {
            throw new OptionsException(502, "Trello couldn't list boards (HTTP " + r.status() + ")");
        }
        List<Option> out = new ArrayList<>();
        for (Object b : r.list()) {
            if (!(b instanceof Map<?, ?> board) || !(board.get("lists") instanceof List<?> lists)) continue;
            lists.stream().filter(l -> l instanceof Map<?, ?>).map(l -> (Map<?, ?>) l)
                    .sorted(Comparator.comparingDouble(l -> l.get("pos") instanceof Number n ? n.doubleValue() : 0))
                    .forEach(l -> out.add(new Option(str(l.get("id")), str(l.get("name")), str(board.get("name")))));
        }
        return out;
    }

    // ---- Stripe ----

    // The account's customers, newest first, a page of 100 at a time.
    private List<Option> stripeCustomers(Account a) {
        List<Option> out = new ArrayList<>();
        String after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            Response r = send(HttpRequest.newBuilder(URI.create(stripeApi + "/customers?limit=100"
                            + (after == null ? "" : "&starting_after=" + URLEncoder.encode(after, StandardCharsets.UTF_8))))
                    .header("Authorization", "Bearer " + a.apiKey())
                    .GET(), "Stripe");
            if (r.status() == 401) {
                rejected(a, "Stripe rejected the API key");
            }
            if (r.status() == 403) {
                throw new OptionsException(422, "This Stripe key can't list customers. Give the restricted key "
                        + "Customers read access, or type the customer ID (cus_...).");
            }
            if (r.status() != 200) {
                throw new OptionsException(502, "Stripe couldn't list customers (HTTP " + r.status() + ")");
            }
            List<?> data = r.map().get("data") instanceof List<?> l ? l : List.of();
            for (Object x : data) {
                if (!(x instanceof Map<?, ?> c) || Boolean.TRUE.equals(c.get("deleted"))) continue;
                String name = str(c.get("name"));
                String email = str(c.get("email"));
                String label = firstNonBlank(name, email, str(c.get("id")));
                String hint = (!name.isBlank() && !email.isBlank() ? email + " · " : "") + str(c.get("id"));
                out.add(new Option(str(c.get("id")), label, hint));
            }
            if (!Boolean.TRUE.equals(r.map().get("has_more")) || data.isEmpty()) break;
            after = str(((Map<?, ?>) data.getLast()).get("id"));
        }
        return out;
    }

    // ---- plumbing ----

    // The app no longer accepts the account: mark it, the same way a failing step does.
    private void rejected(Account a, String reason) {
        Connection c = a.connection();
        tokens.markRejected(c.getId(), c.getUserId(), c.getAppId(), a.version(), reason);
        throw new ConnectionNeedsReauthException(reason + ". Reconnect this account.");
    }

    private record Response(int status, Object body) {
        Map<?, ?> map() {
            return body instanceof Map<?, ?> m ? m : Map.of();
        }

        List<?> list() {
            return body instanceof List<?> l ? l : List.of();
        }
    }

    private Response send(HttpRequest.Builder request, String app) {
        try {
            HttpResponse<String> response = http.send(request.timeout(Duration.ofSeconds(20)).build(),
                    HttpResponse.BodyHandlers.ofString());
            Object body = null;
            if (response.body() != null && !response.body().isBlank()) {
                try {
                    body = jsonMapper.readValue(response.body(), Object.class);
                } catch (RuntimeException notJson) {
                    body = response.body(); // some apps answer errors in plain text (Trello: "invalid token")
                }
            }
            return new Response(response.statusCode(), body);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new OptionsException(502, "Couldn't reach " + app + ". Try again.");
        }
    }

    private static String form(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        params.forEach((k, v) -> {
            if (!out.isEmpty()) out.append('&');
            out.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        return out.toString();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    private static String number(Object v) {
        return v instanceof Number n ? String.valueOf(n.longValue()) : "?";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return "";
    }
}
