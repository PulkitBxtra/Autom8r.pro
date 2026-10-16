package com.bxtralabs.pod.connector.controller;

import com.bxtralabs.pod.connector.connections.ConnectionService;
import com.bxtralabs.pod.connector.connections.ConnectionService.ConnectionView;
import com.bxtralabs.pod.connector.connections.ConnectionService.ConnectorView;
import com.bxtralabs.pod.connector.security.UserAuth;
import com.bxtralabs.pod.connector.triggers.TriggerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class ConnectionController {

    private final UserAuth userAuth;
    private final ConnectionService connectionService;
    private final TriggerService triggers;

    public ConnectionController(UserAuth userAuth, ConnectionService connectionService, TriggerService triggers) {
        this.userAuth = userAuth;
        this.connectionService = connectionService;
        this.triggers = triggers;
    }

    // Apps that can be connected, and how (token fields and/or OAuth).
    @GetMapping("/connectors")
    public List<ConnectorView> connectors(@RequestHeader("Authorization") String authorizationHeader) {
        userAuth.requireUserId(authorizationHeader);
        return connectionService.connectors();
    }

    // The caller's connections, newest first. ?appId= narrows to one app (a step's connection picker).
    @GetMapping("/connections")
    public List<ConnectionView> list(@RequestHeader("Authorization") String authorizationHeader,
                                     @RequestParam(required = false) String appId) {
        return connectionService.list(userAuth.requireUserId(authorizationHeader), appId);
    }

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionView create(@RequestHeader("Authorization") String authorizationHeader,
                                 @Valid @RequestBody CreateTokenConnectionRequest request) {
        return connectionService.createWithToken(userAuth.requireUserId(authorizationHeader),
                request.appId(), request.credentials());
    }

    // Reconnect with new credentials, keeping the same connection id.
    @PutMapping("/connection/{id}")
    public ConnectionView reconnect(@RequestHeader("Authorization") String authorizationHeader,
                                    @PathVariable String id,
                                    @RequestBody ReconnectRequest request) {
        return connectionService.replaceToken(userAuth.requireUserId(authorizationHeader), id, request.credentials());
    }

    @DeleteMapping("/connection/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("Authorization") String authorizationHeader, @PathVariable String id) {
        String userId = userAuth.requireUserId(authorizationHeader);
        connectionService.owned(userId, id); // someone else's: not found, and their triggers untouched
        triggers.connectionRemoved(id);
        connectionService.delete(userId, id);
    }

    public record CreateTokenConnectionRequest(
            @NotBlank(message = "appId is required") String appId,
            Map<String, String> credentials
    ) {}

    public record ReconnectRequest(Map<String, String> credentials) {}
}
