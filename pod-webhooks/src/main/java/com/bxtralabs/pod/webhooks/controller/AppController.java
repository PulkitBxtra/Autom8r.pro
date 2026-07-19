package com.bxtralabs.pod.webhooks.controller;

import com.bxtralabs.pod.webhooks.model.App;
import com.bxtralabs.pod.webhooks.service.AppService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/apps")
public class AppController {

    private final AppService appService;

    public AppController(AppService appService) {
        this.appService = appService;
    }

    // Generic create: POST the app (with its triggers/actions) as JSON.
    @PostMapping
    public App createApp(@RequestBody App app) {
        return appService.createApp(app);
    }
}
