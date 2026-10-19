package com.jtdev.routelisttotesla.controller;

import com.jtdev.routelisttotesla.support.SourceCommit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {
    private final String sourceCommit;

    public HealthController(@Value("${QB_SOURCE_COMMIT:unknown}") String sourceCommit) {
        this.sourceCommit = SourceCommit.normalize(sourceCommit);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "sourceCommit", sourceCommit);
    }
}
