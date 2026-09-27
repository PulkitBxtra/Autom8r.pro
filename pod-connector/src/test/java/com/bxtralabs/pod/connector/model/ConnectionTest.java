package com.bxtralabs.pod.connector.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionTest {

    private static Connection connection() {
        Connection c = new Connection();
        c.setUserId("usr_1");
        c.setAppId("app_github");
        c.setLabel("@octocat");
        c.setAuthType(Connection.AUTH_TOKEN);
        c.setStatus(Connection.STATUS_ACTIVE);
        c.setCredentials("v1:c2VjcmV0LWJsb2I=");
        return c;
    }

    @Test
    void prePersistAssignsAConIdAndTimestamps() {
        Connection c = connection();
        c.prePersist();

        assertTrue(c.getId().startsWith("con_"), c.getId());
        assertEquals(30, c.getId().length(), "con_ + 26-char ULID");
        assertNotNull(c.getCreatedAt());
        assertEquals(c.getCreatedAt(), c.getUpdatedAt());
    }

    @Test
    void prePersistKeepsAnExistingIdAndCreatedAt() {
        Connection c = connection();
        c.setId("con_existing");
        c.setCreatedAt(1000L);
        c.prePersist();

        assertEquals("con_existing", c.getId());
        assertEquals(1000L, c.getCreatedAt());
    }

    @Test
    void preUpdateMovesUpdatedAtOnly() throws Exception {
        Connection c = connection();
        c.prePersist();
        long created = c.getCreatedAt();
        Thread.sleep(2);
        c.preUpdate();

        assertEquals(created, c.getCreatedAt());
        assertTrue(c.getUpdatedAt() > created);
    }

    @Test
    void toStringNeverIncludesCredentials() {
        Connection c = connection();
        String text = c.toString();

        assertFalse(text.contains("v1:"));
        assertFalse(text.contains("c2VjcmV0LWJsb2I="));
        assertTrue(text.contains("app_github") && text.contains("@octocat"));
    }
}
