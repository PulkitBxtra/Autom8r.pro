package com.bxtralabs.pod.connector.internal;

import com.bxtralabs.pod.connector.common.NotFoundException;
import com.bxtralabs.pod.connector.connections.TokenService;
import com.bxtralabs.pod.connector.model.Connection;
import com.bxtralabs.pod.connector.repository.ConnectionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// For pod-processor: the credentials a step needs to call an app, right before it runs.
// OAuth access tokens are refreshed first if they're about to expire (TokenService). The caller
// says whose workflow the step is in and which app it's for, and both must match the
// connection, so a mixed-up id can never hand one user's account to another's workflow.
// Errors: 401 bad internal token, 404 no such connection (for that user and app), 409 the
// connection needs reconnecting, 503 a temporary refresh failure (retry later).
@RestController
public class InternalConnectionController {

    private final InternalAuth auth;
    private final ConnectionRepository connections;
    private final TokenService tokens;

    public InternalConnectionController(InternalAuth auth, ConnectionRepository connections, TokenService tokens) {
        this.auth = auth;
        this.connections = connections;
        this.tokens = tokens;
    }

    public record CredentialsRequest(@NotBlank String userId, @NotBlank String appId) {
    }

    public record CredentialsResponse(String connectionId, String appId, String authType, Map<String, String> credentials) {
    }

    @PostMapping("/internal/connections/{id}/credentials")
    public CredentialsResponse credentials(@RequestHeader(value = InternalAuth.HEADER, required = false) String token,
                                           @PathVariable String id,
                                           @Valid @RequestBody CredentialsRequest request) {
        auth.require(token);
        Connection c = connections.findById(id)
                .filter(found -> found.getUserId().equals(request.userId()) && found.getAppId().equals(request.appId()))
                .orElseThrow(() -> new NotFoundException("Connection not found: " + id));

        Map<String, String> credentials = new LinkedHashMap<>(tokens.getValidCredentials(c.getId()));
        // A step only ever needs the access token; the refresh token stays in this pod.
        credentials.remove(TokenService.REFRESH_TOKEN);
        return new CredentialsResponse(c.getId(), c.getAppId(), c.getAuthType(), credentials);
    }
}
