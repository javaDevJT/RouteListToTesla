package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ImageCacheServiceTest {
    @Test
    void clientCacheKeyIncludesGeocodingVersion() throws Exception {
        ImageCacheService cache = new ImageCacheService();
        String contentHash = "a".repeat(64);
        String owner = "tap-owner";
        String state = "MI";
        String language = (String) ReflectionTestUtils.getField(cache, "ocrLanguage");

        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(AddressOcrService.EXTRACTION_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(GeocodingClient.GEOCODING_VERSION.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(language.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(state.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(owner.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(contentHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(java.util.HexFormat.of().formatHex(digest.digest()),
                cache.calculateClientCacheKey(contentHash, state, owner));
    }

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
