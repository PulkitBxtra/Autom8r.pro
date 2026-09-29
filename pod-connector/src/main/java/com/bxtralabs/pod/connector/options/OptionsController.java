package com.bxtralabs.pod.connector.options;

import com.bxtralabs.pod.connector.security.UserAuth;
import org.springframework.web.bind.annotation.*;

@RestController
public class OptionsController {

    private final UserAuth userAuth;
    private final OptionsService options;

    public OptionsController(UserAuth userAuth, OptionsService options) {
        this.userAuth = userAuth;
        this.options = options;
    }

    // Choices for a step setting from one of the caller's accounts, e.g.
    // GET /connections/con_1/options/slack.channels?q=sup
    @GetMapping("/connections/{id}/options/{source}")
    public OptionsService.Options list(@RequestHeader("Authorization") String authorizationHeader,
                                       @PathVariable String id,
                                       @PathVariable String source,
                                       @RequestParam(required = false) String q) {
        return options.options(userAuth.requireUserId(authorizationHeader), id, source, q);
    }
}
