package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.UserSession;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UserSessionService - session recovery functionality
 */
public class UserSessionServiceTest {

    private static final String TEST_USER_ID = "test-user@example.com";

    @Test
    void testUserSessionModel() {
        // Test the UserSession model directly
        List<PlaceCandidate> addresses = new ArrayList<>();
        addresses.add(new PlaceCandidate("123 Main St", "123 MAIN ST DETROIT MI", "image1.jpg", 0, 42.3314, -83.0458, "ChIJ123"));
        
        UserSession session = new UserSession(TEST_USER_ID, "5YJ3E1EA7JF000000", "MI", addresses, List.of("abc123"));
        
        assertEquals(TEST_USER_ID, session.getUserId());
        assertEquals("5YJ3E1EA7JF000000", session.getVin());
        assertEquals("MI", session.getDefaultState());
        assertEquals(1, session.getAddresses().size());
        assertEquals(1, session.getImageHashes().size());
        assertTrue(session.isValid());
        assertFalse(session.isExpired());
    }

    @Test
    void testSessionExpiration() {
        List<PlaceCandidate> addresses = new ArrayList<>();
        addresses.add(new PlaceCandidate("123 Main St", "123 MAIN ST", "image1.jpg", 0, 42.33, -83.04, "ChIJ123"));
        
        UserSession session = new UserSession();
        session.setUserId(TEST_USER_ID);
        session.setAddresses(addresses);
        session.setImageHashes(List.of("hash1"));
        
        // Initially not expired
        assertFalse(session.isExpired());
        assertTrue(session.isValid());
        
        // Get minutes remaining (should be close to 8 hours = 480 minutes)
        long minutesRemaining = session.getMinutesUntilExpiration();
        assertTrue(minutesRemaining > 470 && minutesRemaining <= 480, 
            "Expected ~480 minutes, got " + minutesRemaining);
    }

    @Test
    void testSessionInvalidWhenNoAddresses() {
        UserSession session = new UserSession();
        session.setUserId(TEST_USER_ID);
        session.setAddresses(new ArrayList<>());
        session.setImageHashes(List.of("hash1"));
        
        // Should be invalid because no addresses
        assertFalse(session.isValid());
    }

    @Test
    void testSessionRefresh() {
        List<PlaceCandidate> addresses = new ArrayList<>();
        addresses.add(new PlaceCandidate("123 Main St", "123 MAIN ST", "image1.jpg", 0, 42.33, -83.04, "ChIJ123"));
        
        UserSession session = new UserSession(TEST_USER_ID, "VIN123", "MI", addresses, List.of("hash1"));
        
        long initialMinutes = session.getMinutesUntilExpiration();
        
        // Refresh extends the session
        session.refresh();
        
        long afterRefresh = session.getMinutesUntilExpiration();
        
        // After refresh, should have close to 8 hours again
        assertTrue(afterRefresh > 470 && afterRefresh <= 480);
    }

    @Test
    void testActiveAutoNavSessionId() {
        List<PlaceCandidate> addresses = new ArrayList<>();
        addresses.add(new PlaceCandidate("123 Main St", "123 MAIN ST", "image1.jpg", 0, 42.33, -83.04, "ChIJ123"));
        
        UserSession session = new UserSession(TEST_USER_ID, "VIN123", "MI", addresses, List.of("hash1"));
        
        // Initially null
        assertNull(session.getActiveAutoNavSessionId());
        
        // Set it
        session.setActiveAutoNavSessionId("auto-nav-12345");
        assertEquals("auto-nav-12345", session.getActiveAutoNavSessionId());
        
        // Clear it
        session.setActiveAutoNavSessionId(null);
        assertNull(session.getActiveAutoNavSessionId());
    }

    @Test
    void testServiceCreation() {
        // Test that service can be created (directory creation)
        UserSessionService service = new UserSessionService();
        assertNotNull(service);
    }

    @Test
    void testSessionNotFound() {
        UserSessionService service = new UserSessionService();
        UserSession loaded = service.loadSession("nonexistent-user-xyz@example.com");
        assertNull(loaded);
    }

    @Test
    void testGetSessionInfoForNonExistentUser() {
        UserSessionService service = new UserSessionService();
        var info = service.getSessionInfo("nonexistent-user-xyz@example.com");
        assertFalse((Boolean) info.get("valid"));
    }
}
