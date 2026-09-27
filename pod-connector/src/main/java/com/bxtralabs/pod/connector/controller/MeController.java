package com.bxtralabs.pod.connector.controller;

import com.bxtralabs.pod.connector.security.UserAuth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Who the caller is, according to this pod. Lets the UI (and tests) confirm pod-connector is
// reachable and accepts the current login.
@RestController
public class MeController {

    private final UserAuth userAuth;

    public MeController(UserAuth userAuth) {
        this.userAuth = userAuth;
    }

    @GetMapping("/me")
    public Map<String, String> me(@RequestHeader("Authorization") String authorizationHeader) {
        return Map.of("userId", userAuth.requireUserId(authorizationHeader));
    }
}
