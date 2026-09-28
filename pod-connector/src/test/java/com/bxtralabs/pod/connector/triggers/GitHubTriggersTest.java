package com.bxtralabs.pod.connector.triggers;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GitHubTriggersTest {

    private final GitHubTriggers github = new GitHubTriggers(JsonMapper.builder().build(), "https://api.github.com");

    @Test
    void anOpenedIssueBecomesTheTriggersData() {
        Map<String, Object> payload = Map.of("action", "opened",
                "repository", Map.of("full_name", "octo/app"),
                "issue", Map.of("number", 12, "title", "Crash", "body", "It crashed", "html_url", "https://github.com/octo/app/issues/12",
                        "user", Map.of("login", "ada"), "labels", List.of(Map.of("name", "bug")), "created_at", "2026-09-28T10:00:00Z"));
        Map<String, Object> body = github.toTriggerBody(GitHubTriggers.NEW_ISSUE, "issues", payload).orElseThrow();
        assertEquals("octo/app", body.get("repository"));
        assertEquals(12, body.get("number"));
        assertEquals("Crash", body.get("title"));
        assertEquals("ada", body.get("author"));
        assertEquals(List.of("bug"), body.get("labels"));
    }

    @Test
    void anOpenedPullRequestCarriesItsBranches() {
        Map<String, Object> payload = Map.of("action", "opened", "repository", Map.of("full_name", "octo/app"),
                "pull_request", Map.of("number", 3, "title", "Fix", "user", Map.of("login", "grace"),
                        "head", Map.of("ref", "fix-crash"), "base", Map.of("ref", "main"), "draft", false));
        Map<String, Object> body = github.toTriggerBody(GitHubTriggers.NEW_PR, "pull_request", payload).orElseThrow();
        assertEquals("fix-crash", body.get("branch"));
        assertEquals("main", body.get("baseBranch"));
    }

    @Test
    void otherActionsAndEventsAreIgnored() {
        assertTrue(github.toTriggerBody(GitHubTriggers.NEW_ISSUE, "issues", Map.of("action", "closed")).isEmpty());
        assertTrue(github.toTriggerBody(GitHubTriggers.NEW_ISSUE, "pull_request", Map.of("action", "opened")).isEmpty());
        assertTrue(github.toTriggerBody(GitHubTriggers.NEW_PR, "issues", Map.of("action", "opened")).isEmpty());
    }

    @Test
    void theSignatureCheckIsHmacSha256() {
        // GitHub's documented example: secret "It's a Secret to Everybody", body "Hello, World!".
        assertTrue(TriggerService.validGitHubSignature("It's a Secret to Everybody", "Hello, World!".getBytes(),
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"));
        assertFalse(TriggerService.validGitHubSignature("It's a Secret to Everybody", "Hello, World?".getBytes(),
                "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"));
    }
}
