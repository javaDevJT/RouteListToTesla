package com.jtdev.routelisttotesla.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AutoNavSession model
 */
public class AutoNavSessionTest {

    @Test
    void testSessionCreation() {
        List<PlaceCandidate> addresses = createTestAddresses(10);
        
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        assertEquals("session-123", session.getSessionId());
        assertEquals("VIN123", session.getVin());
        assertEquals("user@test.com", session.getUserId());
        assertEquals(10, session.getAllAddresses().size());
        assertEquals(2, session.getTotalGroups()); // 10 addresses = 2 groups of 8
        assertEquals(0, session.getCurrentGroupIndex());
        assertEquals(AutoNavSession.Status.CREATED, session.getStatus());
    }

    @Test
    void testGroupCalculation() {
        // Test various address counts
        assertEquals(1, createSessionWithAddresses(1).getTotalGroups());
        assertEquals(1, createSessionWithAddresses(8).getTotalGroups());
        assertEquals(2, createSessionWithAddresses(9).getTotalGroups());
        assertEquals(2, createSessionWithAddresses(16).getTotalGroups());
        assertEquals(3, createSessionWithAddresses(17).getTotalGroups());
        assertEquals(3, createSessionWithAddresses(24).getTotalGroups());
    }

    @Test
    void testGetCurrentGroup() {
        List<PlaceCandidate> addresses = createTestAddresses(20);
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        // First group should have 8 addresses
        List<PlaceCandidate> group1 = session.getCurrentGroup();
        assertEquals(8, group1.size());
        assertEquals("Address 0", group1.get(0).text());
        assertEquals("Address 7", group1.get(7).text());
        
        // Advance to next group
        session.setCurrentGroupIndex(1);
        List<PlaceCandidate> group2 = session.getCurrentGroup();
        assertEquals(8, group2.size());
        assertEquals("Address 8", group2.get(0).text());
        
        // Third group should have 4 addresses (20 - 16 = 4)
        session.setCurrentGroupIndex(2);
        List<PlaceCandidate> group3 = session.getCurrentGroup();
        assertEquals(4, group3.size());
        assertEquals("Address 16", group3.get(0).text());
    }

    @Test
    void testAdvanceToNextGroup() {
        List<PlaceCandidate> addresses = createTestAddresses(20);
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        // Start at group 0
        assertEquals(0, session.getCurrentGroupIndex());
        assertEquals(0, session.getCompletedAddresses());
        assertTrue(session.hasMoreGroups());
        
        // Advance to group 1
        session.advanceToNextGroup();
        assertEquals(1, session.getCurrentGroupIndex());
        assertEquals(8, session.getCompletedAddresses());
        assertTrue(session.hasMoreGroups());
        
        // Advance to group 2
        session.advanceToNextGroup();
        assertEquals(2, session.getCurrentGroupIndex());
        assertEquals(16, session.getCompletedAddresses());
        assertTrue(session.hasMoreGroups());
        
        // Advance past last group - should complete
        session.advanceToNextGroup();
        assertEquals(3, session.getCurrentGroupIndex());
        assertEquals(20, session.getCompletedAddresses());
        assertFalse(session.hasMoreGroups());
        assertEquals(AutoNavSession.Status.COMPLETED, session.getStatus());
    }

    @Test
    void testProgressPercentage() {
        List<PlaceCandidate> addresses = createTestAddresses(20);
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        // Initially 0%
        assertEquals(0, session.getProgressPercentage());
        
        // After first group (8/20 = 40%)
        session.advanceToNextGroup();
        assertEquals(40, session.getProgressPercentage());
        
        // After second group (16/20 = 80%)
        session.advanceToNextGroup();
        assertEquals(80, session.getProgressPercentage());
        
        // After third group (20/20 = 100%)
        session.advanceToNextGroup();
        assertEquals(100, session.getProgressPercentage());
    }

    @Test
    void testStatusTransitions() {
        List<PlaceCandidate> addresses = createTestAddresses(5);
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        // Initial status
        assertEquals(AutoNavSession.Status.CREATED, session.getStatus());
        
        // Start running
        session.setStatus(AutoNavSession.Status.RUNNING);
        assertEquals(AutoNavSession.Status.RUNNING, session.getStatus());
        
        // Stop
        session.setStatus(AutoNavSession.Status.STOPPED);
        assertEquals(AutoNavSession.Status.STOPPED, session.getStatus());
        
        // Error
        session.setStatus(AutoNavSession.Status.ERROR);
        session.setLastError("Connection failed");
        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertEquals("Connection failed", session.getLastError());
    }

    @Test
    void testEmptyAddresses() {
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", List.of());
        
        assertEquals(0, session.getTotalGroups());
        assertFalse(session.hasMoreGroups());
        assertTrue(session.getCurrentGroup().isEmpty());
        assertEquals(0, session.getProgressPercentage());
    }

    @Test
    void testExactlyEightAddresses() {
        List<PlaceCandidate> addresses = createTestAddresses(8);
        AutoNavSession session = new AutoNavSession("session-123", "VIN123", "user@test.com", addresses);
        
        assertEquals(1, session.getTotalGroups());
        assertEquals(8, session.getCurrentGroup().size());
        assertTrue(session.hasMoreGroups());
        
        session.advanceToNextGroup();
        assertFalse(session.hasMoreGroups());
        assertEquals(AutoNavSession.Status.COMPLETED, session.getStatus());
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

    private AutoNavSession createSessionWithAddresses(int count) {
        return new AutoNavSession("session-123", "VIN123", "user@test.com", createTestAddresses(count));
    }
}
