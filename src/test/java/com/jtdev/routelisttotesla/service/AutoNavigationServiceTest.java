package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.FleetApi;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AutoNavigationService
 */
public class AutoNavigationServiceTest {

    private AutoNavigationService autoNavigationService;
    private FleetApi mockFleetApi;
    private GeocodingClient mockGeocodingClient;
    
    private static final String TEST_VIN = "5YJ3E1EA7JF000000";
    private static final String TEST_USER_ID = "test-user@example.com";

    @BeforeEach
    void setUp() {
        mockFleetApi = Mockito.mock(FleetApi.class);
        mockGeocodingClient = Mockito.mock(GeocodingClient.class);
        autoNavigationService = new AutoNavigationService(mockFleetApi, mockGeocodingClient);
    }

    @AfterEach
    void tearDown() {
        // Clean up any test session files
        File sessionsDir = new File("cache/auto-nav-sessions");
        if (sessionsDir.exists()) {
            File[] files = sessionsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }

    @Test
    void testCreateSession() {
        List<PlaceCandidate> addresses = createTestAddresses(10);
        
        AutoNavSession session = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        
        assertNotNull(session);
        assertNotNull(session.getSessionId());
        assertEquals(TEST_VIN, session.getVin());
        assertEquals(TEST_USER_ID, session.getUserId());
        assertEquals(10, session.getAllAddresses().size());
        assertEquals(2, session.getTotalGroups());
        assertEquals(AutoNavSession.Status.CREATED, session.getStatus());
    }

    @Test
    void testGetSession() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        AutoNavSession created = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        
        AutoNavSession retrieved = autoNavigationService.getSession(created.getSessionId());
        
        assertNotNull(retrieved);
        assertEquals(created.getSessionId(), retrieved.getSessionId());
        assertEquals(TEST_VIN, retrieved.getVin());
    }

    @Test
    void testGetSessionNotFound() {
        AutoNavSession retrieved = autoNavigationService.getSession("nonexistent-session-id");
        assertNull(retrieved);
    }

    @Test
    void testGetActiveSessionForUser() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        
        // Initially no active session
        assertNull(autoNavigationService.getActiveSessionForUser(TEST_USER_ID));
        
        // Create a session
        AutoNavSession session = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        
        // Should find the created session
        AutoNavSession active = autoNavigationService.getActiveSessionForUser(TEST_USER_ID);
        assertNotNull(active);
        assertEquals(session.getSessionId(), active.getSessionId());
    }

    @Test
    void testSessionStatusTransition() {
        // Test that we can manually set session to running status
        List<PlaceCandidate> addresses = createTestAddresses(16);
        AutoNavSession session = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        
        // Initially CREATED
        assertEquals(AutoNavSession.Status.CREATED, session.getStatus());
        
        // Manually set to RUNNING (simulating startSession behavior without triggering async)
        session.setStatus(AutoNavSession.Status.RUNNING);
        assertEquals(AutoNavSession.Status.RUNNING, session.getStatus());
        
        // Stop the session
        AutoNavSession stopped = autoNavigationService.stopSession(session.getSessionId());
        assertEquals(AutoNavSession.Status.STOPPED, stopped.getStatus());
    }

    @Test
    void testStartSessionNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            autoNavigationService.startSession("nonexistent-session");
        });
    }

    @Test
    void testStopSession() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        AutoNavSession session = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        session.setStatus(AutoNavSession.Status.RUNNING);
        
        AutoNavSession stopped = autoNavigationService.stopSession(session.getSessionId());
        
        assertEquals(AutoNavSession.Status.STOPPED, stopped.getStatus());
    }

    @Test
    void testStopSessionNotFound() {
        assertThrows(IllegalArgumentException.class, () -> {
            autoNavigationService.stopSession("nonexistent-session");
        });
    }

    @Test
    void testSessionFileCreation() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        AutoNavSession session = autoNavigationService.createSession(TEST_VIN, TEST_USER_ID, addresses);
        String sessionId = session.getSessionId();
        
        // Verify session file exists
        File sessionFile = new File("cache/auto-nav-sessions/" + sessionId + ".json");
        assertTrue(sessionFile.exists(), "Session file should be created");
        assertTrue(sessionFile.length() > 0, "Session file should not be empty");
    }

    @Test
    void testMultipleSessions() {
        List<PlaceCandidate> addresses1 = createTestAddresses(5);
        List<PlaceCandidate> addresses2 = createTestAddresses(10);
        
        AutoNavSession session1 = autoNavigationService.createSession(TEST_VIN, "user1@test.com", addresses1);
        AutoNavSession session2 = autoNavigationService.createSession(TEST_VIN, "user2@test.com", addresses2);
        
        assertNotEquals(session1.getSessionId(), session2.getSessionId());
        
        AutoNavSession retrieved1 = autoNavigationService.getSession(session1.getSessionId());
        AutoNavSession retrieved2 = autoNavigationService.getSession(session2.getSessionId());
        
        assertEquals(5, retrieved1.getAllAddresses().size());
        assertEquals(10, retrieved2.getAllAddresses().size());
    }

    @Test
    void testGetActiveSessionForUserReturnsCorrectSession() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        
        // Create sessions for different users
        autoNavigationService.createSession(TEST_VIN, "user1@test.com", addresses);
        AutoNavSession user2Session = autoNavigationService.createSession(TEST_VIN, "user2@test.com", addresses);
        
        // Get active session for user2
        AutoNavSession active = autoNavigationService.getActiveSessionForUser("user2@test.com");
        
        assertNotNull(active);
        assertEquals(user2Session.getSessionId(), active.getSessionId());
        assertEquals("user2@test.com", active.getUserId());
    }

    // Helper methods
    
    private List<PlaceCandidate> createTestAddresses(int count) {
        List<PlaceCandidate> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            addresses.add(new PlaceCandidate(
                "Address " + i,
                "NORMALIZED ADDRESS " + i,
                "image.jpg",
                i,
                42.0 + (i * 0.001),
                -83.0 + (i * 0.001),
                "ChIJ" + i
            ));
        }
        return addresses;
    }
}
