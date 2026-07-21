package com.bxtralabs.pod.workflow.controller;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthService {

    @GetMapping("/health")
    public String health() {
        return "OK";
    }
}
