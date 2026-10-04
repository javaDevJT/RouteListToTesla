package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImageCacheServiceTest {
    @Test
    void completedExtractionSurvivesRestartWithoutBecomingACompletedImage(@TempDir java.nio.file.Path root) {
        ImageCacheService cache = new ImageCacheService(root);
        String key = cache.calculateClientCacheKey("a".repeat(64), "MI", "owner-a");
        PlaceCandidate row = new PlaceCandidate("123 MAIN ST APT 330 MI", "123 MAIN ST APT 330 MI",
                "original.heic", 2, 0, 0, null, 3, false, java.util.List.of("three engine readings"));
        cache.cacheOcrResults(key, "original.heic", java.util.List.of(row));
        ImageCacheService restarted = new ImageCacheService(root);
        assertEquals(java.util.List.of(row), restarted.getCachedOcrResults(key));
        assertNull(restarted.getCachedResults(key));
        assertFalse(restarted.isCached(key));
        assertNull(restarted.getCachedOcrResults(cache.calculateClientCacheKey("a".repeat(64), "MI", "owner-b")));
        assertNull(restarted.getCachedOcrResults(cache.calculateClientCacheKey("a".repeat(64), "OH", "owner-a")));
        assertEquals(0L, restarted.getCacheStats().get("totalCachedImages"));
        assertEquals(1L, restarted.getCacheStats().get("cachedExtractions"));
        PlaceCandidate resolved = row.withLatLonPid(42.1, -83.1, "street-pid");
        restarted.cacheImageResults(key, "original.heic", java.util.List.of(resolved));
        assertEquals(java.util.List.of(resolved), new ImageCacheService(root).getCachedResults(key));
    }

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
