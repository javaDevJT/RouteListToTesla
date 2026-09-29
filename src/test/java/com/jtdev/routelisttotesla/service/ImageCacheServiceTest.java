package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ImageCacheServiceTest {
    @Test
    void cacheKeySeparatesOwnerStateAndLanguageFromLegacyContentHash() {
        ImageCacheService cache = new ImageCacheService();
        byte[] image = "same image bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String contentHash = cache.calculateImageHash(image, "route.png");
        String ownerAKey = cache.calculateImageHash(image, "route.png", "MI", "tap-owner-a");
        String ownerBKey = cache.calculateImageHash(image, "route.png", "MI", "tap-owner-b");

        assertEquals(cache.calculateClientCacheKey(contentHash, "MI", "tap-owner-a"), ownerAKey);
        assertEquals(64, ownerAKey.length());
        assertNotEquals(contentHash, ownerAKey);
        assertNotEquals(ownerAKey, ownerBKey);
        assertNotEquals(ownerAKey, cache.calculateClientCacheKey(contentHash, "OH", "tap-owner-a"));

        ReflectionTestUtils.setField(cache, "ocrLanguage", "eng+spa");
        String otherLanguageKey = cache.calculateClientCacheKey(contentHash, "MI", "tap-owner-a");
        assertNotEquals(ownerAKey, otherLanguageKey);
        assertNotEquals(contentHash, otherLanguageKey);
    }
}
