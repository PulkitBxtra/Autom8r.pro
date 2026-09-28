package com.bxtralabs.pod.connector.internal;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InternalConnectionControllerTest {

    private static final String TOKEN = "internal-token-0123456789";

    private final ConnectionRepository repo = mock(ConnectionRepository.class);
    private final TokenService tokens = mock(TokenService.class);
    private final InternalConnectionController controller =
            new InternalConnectionController(new InternalAuth(TOKEN), repo, tokens);

    private static Connection connection(String user, String app, String authType) {
        Connection c = new Connection();
        c.setId("con_1");
        c.setUserId(user);
        c.setAppId(app);
        c.setAuthType(authType);
        return c;
    }

    private InternalConnectionController.CredentialsRequest request(String user, String app) {
        return new InternalConnectionController.CredentialsRequest(user, app);
    }

    @Test
    void handsOutTheAccessTokenButNeverTheRefreshToken() {
        when(repo.findById("con_1")).thenReturn(Optional.of(connection("usr_1", "app_github", Connection.AUTH_OAUTH)));
        when(tokens.getValidCredentials("con_1")).thenReturn(Map.of("access_token", "gho_x", "refresh_token", "ghr_y"));

        InternalConnectionController.CredentialsResponse r = controller.credentials(TOKEN, "con_1", request("usr_1", "app_github"));
        assertEquals(Map.of("access_token", "gho_x"), r.credentials());
        assertEquals("OAUTH", r.authType());
    }

    @Test
    void theConnectionMustBelongToTheWorkflowsOwnerAndApp() {
        when(repo.findById("con_1")).thenReturn(Optional.of(connection("usr_1", "app_github", Connection.AUTH_TOKEN)));

        assertThrows(NotFoundException.class, () -> controller.credentials(TOKEN, "con_1", request("usr_2", "app_github")));
        assertThrows(NotFoundException.class, () -> controller.credentials(TOKEN, "con_1", request("usr_1", "app_slack")));
        verify(tokens, never()).getValidCredentials(any());
    }

    @Test
    void callersNeedTheInternalToken() {
        assertThrows(InternalAuth.InternalAuthException.class, () -> controller.credentials(null, "con_1", request("usr_1", "a")));
        assertThrows(InternalAuth.InternalAuthException.class, () -> controller.credentials("wrong-token-0123456789", "con_1", request("usr_1", "a")));
        verifyNoInteractions(repo, tokens);
    }

    @Test
    void withNoOrAShortTokenConfiguredNothingIsAllowed() {
        assertThrows(InternalAuth.InternalAuthException.class, () -> new InternalAuth("").require(""));
        assertThrows(InternalAuth.InternalAuthException.class, () -> new InternalAuth("short").require("short"));
        assertDoesNotThrow(() -> new InternalAuth(TOKEN).require(" " + TOKEN + " "));
    }
}
