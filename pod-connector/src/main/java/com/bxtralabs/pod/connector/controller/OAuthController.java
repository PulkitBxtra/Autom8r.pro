package com.bxtralabs.pod.connector.controller;

import com.bxtralabs.pod.connector.connections.OAuthService;
import com.bxtralabs.pod.connector.security.UserAuth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@RestController
public class OAuthController {

    private final UserAuth userAuth;
    private final OAuthService oauthService;
    private final String frontendUrl;

    public OAuthController(UserAuth userAuth, OAuthService oauthService,
                           @Value("${app.frontend-url:http://localhost:3000}") String frontendUrl) {
        this.userAuth = userAuth;
        this.oauthService = oauthService;
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
    }

    // Called by the UI; returns the provider URL for it to open in a popup.
    @PostMapping("/oauth/{appId}/start")
    public Map<String, String> start(@RequestHeader("Authorization") String authorizationHeader,
                                     @PathVariable String appId,
                                     @RequestBody(required = false) StartRequest request) {
        String userId = userAuth.requireUserId(authorizationHeader);
        String url = oauthService.start(userId, appId, request == null ? null : request.connectionId());
        return Map.of("authorizeUrl", url);
    }

    // The provider redirects the popup here. No login header on this request: who it's for comes
    // from the single-use state. Always redirects back to the UI's small oauth-complete page,
    // which reports the result to the page that opened the popup and closes itself.
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @RequestParam(name = "error_description", required = false) String errorDescription) {
        OAuthService.Result result = oauthService.complete(code, state, error, errorDescription);
        StringBuilder target = new StringBuilder(frontendUrl).append("/oauth-complete?status=")
                .append(result.success() ? "success" : "error");
        if (result.connectionId() != null) {
            target.append("&connectionId=").append(enc(result.connectionId()));
        }
        if (result.appId() != null) {
            target.append("&appId=").append(enc(result.appId()));
        }
        if (result.message() != null) {
            target.append("&message=").append(enc(result.message()));
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(target.toString()));
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    public record StartRequest(String connectionId) {}

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
