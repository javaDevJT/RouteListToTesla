package com.jtdev.routelisttotesla.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void ocrCacheIdentityIsIndependentOfGeocodingPolicyAndRemainsScoped() throws Exception {
        ImageCacheService cache = new ImageCacheService();
        String contentHash = "b".repeat(64);
        String owner = "tap-owner";
        String state = "MI";
        String language = (String) ReflectionTestUtils.getField(cache, "ocrLanguage");

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(AddressOcrService.EXTRACTION_VERSION.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(language.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(state.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(owner.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(contentHash.getBytes(StandardCharsets.UTF_8));
        String expectedOcrKey = HexFormat.of().formatHex(digest.digest());

        assertEquals(expectedOcrKey, cache.calculateOcrCacheKey(contentHash, state, owner));
        assertNotEquals(cache.calculateClientCacheKey(contentHash, state, owner), expectedOcrKey);
        assertNotEquals(expectedOcrKey, cache.calculateOcrCacheKey(contentHash, "OH", owner));
        assertNotEquals(expectedOcrKey, cache.calculateOcrCacheKey(contentHash, state, "another-owner"));
        assertNotEquals(expectedOcrKey, cache.calculateOcrCacheKey("c".repeat(64), state, owner));

        ReflectionTestUtils.setField(cache, "ocrLanguage", "eng+spa");
        assertNotEquals(expectedOcrKey, cache.calculateOcrCacheKey(contentHash, state, owner));
    }

    @Test
    void migratesExactLegacyV3OcrEntryWithoutMigratingGeocodedResults(@TempDir Path root) throws Exception {
        String contentHash = "d".repeat(64);
        String state = "MI";
        String owner = "owner-a";
        String oldCombinedKey = legacyV3Key(contentHash, state, owner, AddressOcrService.EXTRACTION_VERSION, "eng");
        Path oldOcrFile = root.resolve("images").resolve(oldCombinedKey + ".ocr.json");
        Files.createDirectories(oldOcrFile.getParent());

        PlaceCandidate legacyRow = new PlaceCandidate("123 MAIN ST APT 3 MI", "123 MAIN ST APT 3 MI",
                "route.heic", 4, 42.1, -83.1, "legacy-pid", 2, true, List.of("123 MAIN ST"),
                new LocationResolution(LocationResolution.Status.VERIFIED_UNIT, "legacy", "OK", 1,
                        "street-address-v3", null));
        // Legacy rows predate LocationResolution, so omit that JSON property entirely.
        Files.writeString(oldOcrFile, """
                {"imageHash":"%s.ocr","originalFilename":"route.heic","candidates":[{"text":"123 MAIN ST APT 3 MI","normalized":"123 MAIN ST APT 3 MI","sourceImage":"route.heic","lineIndex":4,"lat":42.1,"lon":-83.1,"pid":"legacy-pid","ocrAgreement":2,"ocrReviewRequired":true,"ocrAlternatives":["123 MAIN ST"]}],"timestamp":"2026-10-17T12:00:00","candidateCount":1}
                """.formatted(oldCombinedKey));

        String geocodedOnlyHash = "e".repeat(64);
        String geocodedOnlyKey = legacyV3Key(geocodedOnlyHash, state, owner,
                AddressOcrService.EXTRACTION_VERSION, "eng");
        Path oldGeocodedFile = root.resolve("images").resolve(geocodedOnlyKey + ".json");
        ImageCacheService.CacheEntry oldGeocodedEntry = cacheEntry(geocodedOnlyKey, "route.heic",
                List.of(legacyRow.withLatLonPid(42.2, -83.2, "geocoded-only-pid")));
        JsonMapper.builderWithJackson2Defaults().build().writeValue(oldGeocodedFile.toFile(), oldGeocodedEntry);

        String isolationHash = "a".repeat(64);
        String wrongExtractionKey = legacyV3Key(isolationHash, state, owner,
                AddressOcrService.EXTRACTION_VERSION + "-old", "eng");
        String wrongLanguageKey = legacyV3Key(isolationHash, state, owner,
                AddressOcrService.EXTRACTION_VERSION, "eng+spa");
        JsonMapper isolationMapper = JsonMapper.builderWithJackson2Defaults().build();
        isolationMapper.writeValue(root.resolve("images").resolve(wrongExtractionKey + ".ocr.json").toFile(),
                cacheEntry(wrongExtractionKey + ".ocr", "route.heic", List.of(legacyRow)));
        isolationMapper.writeValue(root.resolve("images").resolve(wrongLanguageKey + ".ocr.json").toFile(),
                cacheEntry(wrongLanguageKey + ".ocr", "route.heic", List.of(legacyRow)));

        ImageCacheService cache = new ImageCacheService(root);
        PlaceCandidate expectedOcrRow = new PlaceCandidate("123 MAIN ST APT 3 MI", "123 MAIN ST APT 3 MI",
                "route.heic", 4, 0, 0, null, 2, true, List.of("123 MAIN ST"));
        assertEquals(List.of(expectedOcrRow), cache.getCachedOcrResults(contentHash, state, owner));

        String currentOcrKey = cache.calculateOcrCacheKey(contentHash, state, owner);
        assertTrue(Files.exists(root.resolve("images").resolve(currentOcrKey + ".ocr.json")));
        assertTrue(Files.exists(oldOcrFile));
        assertNull(cache.getCachedOcrResults(contentHash, state, "owner-b"));
        assertNull(cache.getCachedOcrResults(contentHash, "OH", owner));
        assertNull(cache.getCachedOcrResults(geocodedOnlyHash, state, owner));
        assertNull(cache.getCachedOcrResults(isolationHash, state, owner));
        assertTrue(Files.exists(oldGeocodedFile));
    }

    @Test
    void newOcrWritesStripLocationButPreserveOcrProvenance(@TempDir Path root) {
        ImageCacheService cache = new ImageCacheService(root);
        String contentHash = "f".repeat(64);
        String state = "MI";
        String owner = "owner-a";
        PlaceCandidate resolved = new PlaceCandidate("20 MAIN ST APT 4 MI", "20 MAIN ST APT 4 MI",
                "capture.png", 6, 42.3, -83.3, "pid-20", 3, false, List.of("20 MAIN ST"),
                new LocationResolution(LocationResolution.Status.VERIFIED_UNIT, "full", "OK", 1,
                        "street-address-v4", null));

        cache.cacheOcrResults(contentHash, state, owner, "capture.png", List.of(resolved));

        String ocrKey = cache.calculateOcrCacheKey(contentHash, state, owner);
        PlaceCandidate persisted = cache.getCachedResults(ocrKey + ".ocr").getFirst();
        assertEquals(new PlaceCandidate("20 MAIN ST APT 4 MI", "20 MAIN ST APT 4 MI",
                "capture.png", 6, 0, 0, null, 3, false, List.of("20 MAIN ST")), persisted);
        assertNull(cache.getCachedResults(ocrKey));
        assertEquals(List.of(persisted), cache.getCachedOcrResults(contentHash, state, owner));
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

    private static ImageCacheService.CacheEntry cacheEntry(String key, String filename,
                                                           List<PlaceCandidate> rows) {
        ImageCacheService.CacheEntry entry = new ImageCacheService.CacheEntry();
        entry.imageHash = key;
        entry.originalFilename = filename;
        entry.candidates = rows;
        entry.timestamp = java.time.LocalDateTime.now().toString();
        entry.candidateCount = rows.size();
        return entry;
    }

    private static String legacyV3Key(String contentHash, String state, String owner,
                                      String extractionVersion, String language) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(extractionVersion.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update("street-address-v3".getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(language.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(state.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(owner.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(contentHash.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
}
