package com.jtdev.routelisttotesla.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AutoNavSessionTest {
    @Test
    void constructorCopiesAddressListAndGroupsAtEightWaypoints() {
        List<PlaceCandidate> input = addresses(17);
        AutoNavSession session = new AutoNavSession("session-1", "vin", "driver@example.com", input);

        input.clear();

        assertEquals(17, session.getAllAddresses().size());
        assertEquals(3, session.getTotalGroups());
        assertEquals(8, session.getCurrentGroup().size());
        assertEquals("Address 0", session.getCurrentGroup().get(0).text());

        session.setCurrentGroupIndex(2);
        assertEquals(1, session.getCurrentGroup().size());
        assertEquals("Address 16", session.getCurrentGroup().get(0).text());
    }

    @Test
    void progressAdvancesOneWaypointOnlyAfterAcceptedCommandAndArrival() {
        AutoNavSession session = new AutoNavSession("session-2", "vin", "driver@example.com", addresses(9));

        assertFalse(session.confirmCurrentWaypoint(Instant.parse("2026-09-26T12:00:00Z")));
        assertEquals(0, session.getCompletedAddresses());

        session.setCommandState(AutoNavSession.CommandState.ACCEPTED);
        Instant arrival = Instant.parse("2026-09-26T12:00:00Z");
        for (int i = 0; i < 7; i++) {
            assertFalse(session.confirmCurrentWaypoint(arrival.plusSeconds(i)));
            assertEquals(i + 1, session.getCompletedAddresses());
            assertEquals(i + 1, session.getCurrentWaypointIndex());
        }

        assertTrue(session.confirmCurrentWaypoint(arrival.plusSeconds(7)));
        assertEquals(8, session.getCompletedAddresses());
        assertEquals(1, session.getCurrentGroupIndex());
        assertEquals(8, session.getCurrentWaypointIndex());
        assertTrue(session.isNextGroupReady());
        assertEquals(AutoNavSession.CommandState.NONE, session.getCommandState());
        assertEquals(88, session.getProgressPercentage());
        assertTrue(session.hasMoreGroups());
        assertEquals(AutoNavSession.Status.CREATED, session.getStatus());

        session.setCommandState(AutoNavSession.CommandState.ACCEPTED);
        assertTrue(session.confirmCurrentWaypoint(arrival.plusSeconds(8)));
        assertEquals(9, session.getCompletedAddresses());
        assertEquals(100, session.getProgressPercentage());
        assertEquals(AutoNavSession.Status.COMPLETED, session.getStatus());
        assertFalse(session.isNextGroupReady());
        assertFalse(session.hasMoreGroups());
    }

    @Test
    void emptyRouteHasNoProgressOrCurrentWaypoint() {
        AutoNavSession session = new AutoNavSession("session-3", "vin", "driver@example.com", List.of());

        assertTrue(session.getCurrentGroup().isEmpty());
        assertNull(session.getCurrentWaypoint());
        assertEquals(0, session.getTotalGroups());
        assertEquals(0, session.getProgressPercentage());
        assertFalse(session.hasMoreGroups());
    }

    private static List<PlaceCandidate> addresses(int count) {
        List<PlaceCandidate> addresses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            addresses.add(new PlaceCandidate("Address " + i, "ADDRESS " + i, "image.png", i,
                    42.0 + i * 0.001, -83.0, "place-" + i));
        }
        return addresses;
    }
}
