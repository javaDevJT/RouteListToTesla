package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.UserSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserSessionMigrationTest {
    @TempDir Path directory;

    @Test
    void preservesSavedRouteAndExpiryWithoutOverwritingOrDeletingTheOriginal() throws Exception {
        var service = new UserSessionService(directory);
        var candidate = new PlaceCandidate("123 Main St", "123 Main St", "route.png", 2, 42, -83, "place");
        var legacy = new UserSession("owner@example.test", "5YJ3E1EA7JF000000", "MI", List.of(candidate), List.of("a".repeat(64)));
        legacy.setActiveAutoNavSessionId("route-id");
        service.saveSession(legacy);
        byte[] backup = Files.readAllBytes(directory.resolve("owner@example.test.json"));
        service.migrateOwner("owner@example.test", "stable-tap-subject");
        var migrated = service.loadSession("stable-tap-subject");
        assertEquals("stable-tap-subject", migrated.getUserId());
        assertEquals(legacy.getAddresses(), migrated.getAddresses());
        assertEquals(legacy.getImageHashes(), migrated.getImageHashes());
        assertEquals(legacy.getVin(), migrated.getVin());
        assertEquals(legacy.getDefaultState(), migrated.getDefaultState());
        assertEquals(legacy.getExpiresAt(), migrated.getExpiresAt());
        assertEquals(legacy.getActiveAutoNavSessionId(), migrated.getActiveAutoNavSessionId());
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("owner@example.test.json")));
        migrated.setVin("7G2CEHEE8SA000000");
        service.saveSession(migrated);
        service.migrateOwner("owner@example.test", "stable-tap-subject");
        assertEquals(migrated.getVin(), service.loadSession("stable-tap-subject").getVin());
    }

    @Test
    void doesNotClaimMismatchedLegacyData() throws Exception {
        var service = new UserSessionService(directory);
        var session = new UserSession("another@example.test", "5YJ3E1EA7JF000000", "MI", List.of(), List.of());
        service.saveSession(session);
        Files.move(directory.resolve("another@example.test.json"), directory.resolve("owner@example.test.json"));
        service.migrateOwner("owner@example.test", "stable-tap-subject");
        assertFalse(Files.exists(directory.resolve("stable-tap-subject.json")));
        assertTrue(Files.exists(directory.resolve("owner@example.test.json")));
    }

    @Test
    void readsLegacyJsonWithIsoTimestampsMutableCollectionsAndNullableCandidateFields() throws Exception {
        Files.writeString(directory.resolve("legacy-user.json"), """
                {
                  "userId": "legacy-user",
                  "vin": null,
                  "defaultState": "MI",
                  "addresses": [
                    {
                      "text": "123 Main St",
                      "normalized": null,
                      "sourceImage": null,
                      "lineIndex": 2,
                      "lat": 42.1,
                      "lon": -83.1,
                      "pid": null
                    }
                  ],
                  "imageHashes": ["original-hash"],
                  "createdAt": "2099-02-03T04:05:06",
                  "expiresAt": "2099-02-03T12:05:06",
                  "activeAutoNavSessionId": null
                }
                """);

        var service = new UserSessionService(directory);
        var loaded = service.loadSession("legacy-user");

        assertNotNull(loaded);
        assertEquals(LocalDateTime.parse("2099-02-03T04:05:06"), loaded.getCreatedAt());
        assertEquals(LocalDateTime.parse("2099-02-03T12:05:06"), loaded.getExpiresAt());
        assertNull(loaded.getVin());
        assertNull(loaded.getActiveAutoNavSessionId());

        var candidate = loaded.getAddresses().getFirst();
        assertNull(candidate.normalized());
        assertNull(candidate.sourceImage());
        assertNull(candidate.pid());
        assertEquals(List.of(), candidate.ocrAlternatives());
        assertEquals(0, candidate.ocrAgreement());
        assertFalse(candidate.ocrReviewRequired());

        loaded.getAddresses().add(new PlaceCandidate("456 Oak Ave", "456 Oak Ave", "next.png", 0, 42.2, -83.2, "next"));
        loaded.getImageHashes().add("second-hash");
        service.saveSession(loaded);

        String persistedJson = Files.readString(directory.resolve("legacy-user.json"));
        assertTrue(persistedJson.contains("\"createdAt\":\"2099-02-03T04:05:06\""));
        assertTrue(persistedJson.contains("\"expiresAt\":\"2099-02-03T12:05:06\""));
        var roundTripped = service.loadSession("legacy-user");
        assertNotNull(roundTripped);
        assertEquals(2, roundTripped.getAddresses().size());
        assertEquals(List.of("original-hash", "second-hash"), roundTripped.getImageHashes());
    }
}
