package com.bxtralabs.pod.backend.controller;

import com.bxtralabs.pod.backend.catalog.CatalogApp;
import com.bxtralabs.pod.backend.catalog.CatalogService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@RestController
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    // Public: the catalog is the same for everyone and holds nothing private (the landing page
    // lists the apps too). Only changes with a deploy, so browsers may cache it briefly.
    @GetMapping("/apps")
    public ResponseEntity<List<CatalogApp>> apps() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(catalogService.apps());
    }
}
