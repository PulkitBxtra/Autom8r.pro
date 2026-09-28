package com.bxtralabs.pod.connector.controller;

import com.bxtralabs.pod.connector.connections.OAuthClientService;
import com.bxtralabs.pod.connector.connections.OAuthClientService.OAuthClientView;
import com.bxtralabs.pod.connector.security.UserAuth;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// The user's own OAuth apps, used instead of the server's when signing in to an app.
@RestController
public class OAuthClientController {

    private final UserAuth userAuth;
    private final OAuthClientService clients;

    public OAuthClientController(UserAuth userAuth, OAuthClientService clients) {
        this.userAuth = userAuth;
        this.clients = clients;
    }

    // ?provider= narrows to one provider (the picker in "Connect with GitHub").
    @GetMapping("/oauth-clients")
    public List<OAuthClientView> list(@RequestHeader("Authorization") String authorizationHeader,
                                      @RequestParam(required = false) String provider) {
        return clients.list(userAuth.requireUserId(authorizationHeader), provider);
    }

    @PostMapping("/oauth-clients")
    @ResponseStatus(HttpStatus.CREATED)
    public OAuthClientView create(@RequestHeader("Authorization") String authorizationHeader,
                                  @Valid @RequestBody CreateRequest request) {
        return clients.create(userAuth.requireUserId(authorizationHeader), request.provider(), request.name(),
                request.clientId(), request.clientSecret());
    }

    // Rename and/or replace the secret; a blank secret keeps the current one.
    @PutMapping("/oauth-client/{id}")
    public OAuthClientView update(@RequestHeader("Authorization") String authorizationHeader,
                                  @PathVariable String id, @RequestBody UpdateRequest request) {
        return clients.update(userAuth.requireUserId(authorizationHeader), id, request.name(), request.clientSecret());
    }

    // 409 while connections still use it.
    @DeleteMapping("/oauth-client/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader("Authorization") String authorizationHeader, @PathVariable String id) {
        clients.delete(userAuth.requireUserId(authorizationHeader), id);
    }

    public record CreateRequest(
            @NotBlank(message = "provider is required") String provider,
            String name,
            @NotBlank(message = "Client ID is required") String clientId,
            @NotBlank(message = "Client secret is required") String clientSecret
    ) {}

    public record UpdateRequest(String name, String clientSecret) {}
}
