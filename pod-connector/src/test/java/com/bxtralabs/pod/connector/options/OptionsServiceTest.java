package com.bxtralabs.pod.connector.options;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.ConnectionNeedsReauthException;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OptionsServiceTest {

    private final ConnectionRepository connections = mock(ConnectionRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    // path -> body; "status:body" for a non-200 answer
    private final Map<String, String> answers = new HashMap<>();
    private final List<String> calls = new ArrayList<>();
    private long now = 1_000_000;
    private HttpServer server;
    private OptionsService service;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = ex.getRequestURI().getPath();
            calls.add(path + (ex.getRequestURI().getQuery() == null ? "" : "?" + ex.getRequestURI().getQuery()) + " " + body
                    + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String key = path + (body.contains("cursor=next") ? "#2" : "")
                    + (ex.getRequestURI().getQuery() != null && ex.getRequestURI().getQuery().contains("page=2") ? "#2" : "");
            String answer = answers.getOrDefault(key, "404:{}");
            int status = 200;
            if (answer.matches("^\\d{3}:.*")) {
                status = Integer.parseInt(answer.substring(0, 3));
                answer = answer.substring(4);
            }
            byte[] out = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        service = new OptionsService(connections, tokens, JsonMapper.builder().build(),
                base + "/gh", base + "/slack", base + "/notion", base + "/discord", base + "/trello", base + "/trello-oauth", base + "/stripe", () -> now);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Connection account(String id, String appId, String userId) {
        Connection c = new Connection();
        c.setId(id);
        c.setUserId(userId);
        c.setAppId(appId);
        c.setStatus(Connection.STATUS_ACTIVE);
        c.setCredentials("ciphertext-" + id);
        when(connections.findById(id)).thenReturn(Optional.of(c));
        when(tokens.getValidCredentials(id)).thenReturn(Map.of("access_token", "tok-" + id));
        return c;
    }

    @Test
    void slackChannelsComeFromEveryPageSortedAndSayWhereTheBotIsMissing() {
        account("con_s", "app_slack", "usr_1");
        answers.put("/slack/conversations.list", "{\"ok\":true,\"channels\":[{\"id\":\"C2\",\"name\":\"support\",\"num_members\":12,\"is_member\":true}],"
                + "\"response_metadata\":{\"next_cursor\":\"next\"}}");
        answers.put("/slack/conversations.list#2", "{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\",\"num_members\":40,\"is_member\":false}]}");

        OptionsService.Options got = service.options("usr_1", "con_s", "slack.channels", null);

        assertEquals(List.of(new OptionsService.Option("C1", "#general", "40 members · bot not in it yet"),
                new OptionsService.Option("C2", "#support", "12 members")), got.options());
        assertFalse(got.more());
        assertTrue(calls.getFirst().endsWith("Bearer tok-con_s"));
        assertTrue(calls.getFirst().contains("types=public_channel"));
    }

    @Test
    void typingFiltersTheCachedListWithoutCallingSlackAgain() {
        account("con_s", "app_slack", "usr_1");
        answers.put("/slack/users.list", "{\"ok\":true,\"members\":["
                + "{\"id\":\"U1\",\"name\":\"ada\",\"profile\":{\"real_name\":\"Ada Lovelace\",\"display_name\":\"ada\"}},"
                + "{\"id\":\"U2\",\"name\":\"grace\",\"profile\":{\"real_name\":\"Grace Hopper\"}},"
                + "{\"id\":\"U3\",\"name\":\"gone\",\"deleted\":true},"
                + "{\"id\":\"U4\",\"name\":\"somebot\",\"is_bot\":true},"
                + "{\"id\":\"USLACKBOT\",\"name\":\"slackbot\"}]}");

        assertEquals(List.of("Ada Lovelace", "Grace Hopper"),
                service.options("usr_1", "con_s", "slack.users", "").options().stream().map(OptionsService.Option::label).toList());
        assertEquals(List.of(new OptionsService.Option("U2", "Grace Hopper", "@grace")),
                service.options("usr_1", "con_s", "slack.users", "GRA").options());
        assertEquals(1, calls.size(), "the second lookup used the kept list");

        now += OptionsService.CACHE_MS;
        service.options("usr_1", "con_s", "slack.users", "");
        assertEquals(2, calls.size(), "after a minute the list is read again");
    }

    @Test
    void aMissingSlackPermissionSaysWhichOne() {
        account("con_s", "app_slack", "usr_1");
        answers.put("/slack/users.list", "{\"ok\":false,\"error\":\"missing_scope\",\"needed\":\"users:read\"}");
        OptionsException e = assertThrows(OptionsException.class, () -> service.options("usr_1", "con_s", "slack.users", ""));
        assertEquals(422, e.status());
        assertTrue(e.getMessage().contains("users:read"), e.getMessage());
    }

    @Test
    void aRevokedTokenMarksTheAccountForReconnecting() {
        Connection c = account("con_s", "app_slack", "usr_1");
        answers.put("/slack/conversations.list", "{\"ok\":false,\"error\":\"token_revoked\"}");
        assertThrows(ConnectionNeedsReauthException.class, () -> service.options("usr_1", "con_s", "slack.channels", ""));
        verify(tokens).markRejected(eq("con_s"), eq("usr_1"), eq("app_slack"), eq(TokenService.version(c)), contains("token_revoked"));
    }

    @Test
    void githubReposArePagedAndSavedAsOwnerSlashName() {
        account("con_g", "app_github", "usr_1");
        StringBuilder page1 = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            page1.append(i == 0 ? "" : ",").append("{\"full_name\":\"acme/repo").append(i).append("\",\"private\":").append(i == 0).append("}");
        }
        answers.put("/gh/user/repos", page1.append("]").toString());
        answers.put("/gh/user/repos#2", "[{\"full_name\":\"acme/last\",\"private\":false,\"description\":\"The last one\"}]");

        OptionsService.Options all = service.options("usr_1", "con_g", "github.repos", null);
        assertEquals(OptionsService.LIMIT, all.options().size());
        assertTrue(all.more(), "101 repositories, 100 shown");
        assertEquals(new OptionsService.Option("acme/repo0", "acme/repo0", "Private"), all.options().getFirst());
        assertEquals(List.of(new OptionsService.Option("acme/last", "acme/last", "Public · The last one")),
                service.options("usr_1", "con_g", "github.repos", "last").options());

        answers.put("/gh/user/repos", "401:{\"message\":\"Bad credentials\"}");
        now += OptionsService.CACHE_MS;
        assertThrows(ConnectionNeedsReauthException.class, () -> service.options("usr_1", "con_g", "github.repos", null));
    }

    @Test
    void notionIsSearchedByNotionWithTheKindAsked() {
        account("con_n", "app_notion", "usr_1");
        answers.put("/notion/search", "{\"results\":["
                + "{\"object\":\"database\",\"id\":\"db-1\",\"title\":[{\"plain_text\":\"Tasks\"}]},"
                + "{\"object\":\"page\",\"id\":\"pg-1\",\"properties\":{\"Name\":{\"type\":\"title\",\"title\":[{\"plain_text\":\"Road\"},{\"plain_text\":\"map\"}]}}},"
                + "{\"object\":\"page\",\"id\":\"pg-2\",\"properties\":{}}]}");

        List<OptionsService.Option> got = service.options("usr_1", "con_n", "notion.parents", "ro").options();

        assertEquals(List.of(new OptionsService.Option("db-1", "Tasks", "Database"), new OptionsService.Option("pg-1", "Roadmap", "Page"),
                new OptionsService.Option("pg-2", "Untitled", "Page")), got, "Notion already matched the query");
        assertTrue(calls.getFirst().contains("\"query\":\"ro\"") && !calls.getFirst().contains("filter"), calls.getFirst());

        service.options("usr_1", "con_n", "notion.databases", "ro");
        assertTrue(calls.getLast().contains("\"value\":\"database\""), calls.getLast());
    }

    @Test
    void discordChannelsAreTheTextChannelsOfEveryServerTheBotIsIn() {
        account("con_d", "app_discord", "usr_1");
        answers.put("/discord/users/@me/guilds", "[{\"id\":\"100\",\"name\":\"Acme\"}]");
        answers.put("/discord/guilds/100/channels", "["
                + "{\"id\":\"3\",\"name\":\"releases\",\"type\":5,\"position\":1,\"parent_id\":\"9\"},"
                + "{\"id\":\"2\",\"name\":\"general\",\"type\":0,\"position\":0,\"parent_id\":\"9\"},"
                + "{\"id\":\"4\",\"name\":\"Voice\",\"type\":2,\"position\":2},"
                + "{\"id\":\"5\",\"name\":\"rules\",\"type\":0,\"position\":0,\"parent_id\":\"8\"},"
                + "{\"id\":\"6\",\"name\":\"lobby\",\"type\":0,\"position\":3},"
                + "{\"id\":\"8\",\"name\":\"Welcome\",\"type\":4,\"position\":0},"
                + "{\"id\":\"9\",\"name\":\"Text Channels\",\"type\":4,\"position\":1}]");

        OptionsService.Options got = service.options("usr_1", "con_d", "discord.channels", null);

        // As Discord shows them: no category first, then categories by position, not by name.
        assertEquals(List.of(new OptionsService.Option("6", "#lobby", "Acme"),
                new OptionsService.Option("5", "#rules", "Acme · Welcome"),
                new OptionsService.Option("2", "#general", "Acme · Text Channels"),
                new OptionsService.Option("3", "#releases", "Acme · Text Channels")), got.options());
        assertTrue(calls.getFirst().endsWith(" Bot tok-con_d"), calls.getFirst());
    }

    @Test
    void trelloListsAreTheOpenListsOfEachBoardInOrder() {
        Connection c = account("con_t", "app_trello", "usr_1");
        when(tokens.getValidCredentials("con_t")).thenReturn(Map.of("apiKey", "key-1", "token", "tok-1"));
        answers.put("/trello/members/me/boards", "[{\"id\":\"b1\",\"name\":\"Roadmap\",\"lists\":["
                + "{\"id\":\"l2\",\"name\":\"Done\",\"pos\":3000},{\"id\":\"l1\",\"name\":\"To do\",\"pos\":1000}]},"
                + "{\"id\":\"b2\",\"name\":\"Empty\",\"lists\":[]}]");

        OptionsService.Options got = service.options("usr_1", "con_t", "trello.lists", null);

        assertEquals(List.of(new OptionsService.Option("l1", "To do", "Roadmap"), new OptionsService.Option("l2", "Done", "Roadmap")),
                got.options());
        assertTrue(calls.getFirst().endsWith(" OAuth oauth_consumer_key=\"key-1\", oauth_token=\"tok-1\""), calls.getFirst());
        assertFalse(calls.getFirst().contains("tok-1&") || calls.getFirst().contains("token=tok"), "not in the URL");

        // Signed in with Trello: an access token, no key, and Trello's OAuth address.
        account("con_o", "app_trello", "usr_1");
        answers.put("/trello-oauth/members/me/boards", "[{\"id\":\"b1\",\"name\":\"Roadmap\",\"lists\":[{\"id\":\"l1\",\"name\":\"To do\",\"pos\":1}]}]");
        assertEquals(List.of(new OptionsService.Option("l1", "To do", "Roadmap")),
                service.options("usr_1", "con_o", "trello.lists", null).options());
        assertTrue(calls.getLast().startsWith("/trello-oauth/members/me/boards") && calls.getLast().endsWith(" Bearer tok-con_o"), calls.getLast());

        answers.put("/trello/members/me/boards", "401:invalid token");
        now += OptionsService.CACHE_MS;
        assertThrows(ConnectionNeedsReauthException.class, () -> service.options("usr_1", "con_t", "trello.lists", ""));
        verify(tokens).markRejected(eq("con_t"), eq("usr_1"), eq("app_trello"), eq(TokenService.version(c)), contains("Trello"));
    }

    @Test
    void stripeCustomersAreNamedByNameOrEmailAndPaged() {
        Connection c = account("con_p", "app_stripe", "usr_1");
        when(tokens.getValidCredentials("con_p")).thenReturn(Map.of("apiKey", "sk_test_1"));
        answers.put("/stripe/customers", "{\"data\":[{\"id\":\"cus_A\",\"name\":\"Ada Lovelace\",\"email\":\"ada@example.com\"},"
                + "{\"id\":\"cus_B\",\"name\":null,\"email\":\"grace@example.com\"}],\"has_more\":false}");

        assertEquals(List.of(new OptionsService.Option("cus_A", "Ada Lovelace", "ada@example.com · cus_A"),
                new OptionsService.Option("cus_B", "grace@example.com", "cus_B")),
                service.options("usr_1", "con_p", "stripe.customers", null).options());
        assertTrue(calls.getFirst().endsWith(" Bearer sk_test_1"), calls.getFirst());

        answers.put("/stripe/customers", "403:{\"error\":{\"message\":\"The provided key does not have the required permissions\"}}");
        now += OptionsService.CACHE_MS;
        assertEquals(422, assertThrows(OptionsException.class, () -> service.options("usr_1", "con_p", "stripe.customers", "")).status());
        answers.put("/stripe/customers", "401:{\"error\":{\"message\":\"Invalid API Key provided\"}}");
        now += OptionsService.CACHE_MS;
        assertThrows(ConnectionNeedsReauthException.class, () -> service.options("usr_1", "con_p", "stripe.customers", ""));
        verify(tokens).markRejected(eq("con_p"), eq("usr_1"), eq("app_stripe"), eq(TokenService.version(c)), contains("Stripe"));
    }

    @Test
    void aBotInNoServerIsToldToInviteIt() {
        account("con_d", "app_discord", "usr_1");
        answers.put("/discord/users/@me/guilds", "[]");
        OptionsException e = assertThrows(OptionsException.class, () -> service.options("usr_1", "con_d", "discord.channels", ""));
        assertEquals(422, e.status());
        assertTrue(e.getMessage().startsWith("This bot isn't in any Discord server yet"), e.getMessage());
    }

    @Test
    void aRejectedDiscordTokenMarksTheAccountForReconnecting() {
        Connection c = account("con_d", "app_discord", "usr_1");
        answers.put("/discord/users/@me/guilds", "401:{\"message\":\"401: Unauthorized\",\"code\":0}");
        assertThrows(ConnectionNeedsReauthException.class, () -> service.options("usr_1", "con_d", "discord.channels", ""));
        verify(tokens).markRejected(eq("con_d"), eq("usr_1"), eq("app_discord"), eq(TokenService.version(c)), contains("Discord"));
    }

    @Test
    void onlyTheOwnersAccountsAndTheirOwnAppsLists() {
        account("con_s", "app_slack", "usr_1");
        assertThrows(NotFoundException.class, () -> service.options("usr_2", "con_s", "slack.channels", ""));
        assertThrows(IllegalArgumentException.class, () -> service.options("usr_1", "con_s", "github.repos", ""));
        assertThrows(NotFoundException.class, () -> service.options("usr_1", "con_s", "slack.emoji", ""));
        assertTrue(calls.isEmpty());
        verify(tokens, never()).getValidCredentials(any());
    }
}
