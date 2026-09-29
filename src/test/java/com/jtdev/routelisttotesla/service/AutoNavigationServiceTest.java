package com.jtdev.routelisttotesla.service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.service.TapVehicleClient.CommandResult;
import com.jtdev.routelisttotesla.service.TapVehicleClient.Telemetry;
import com.jtdev.routelisttotesla.service.TapVehicleClient.TelemetryPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AutoNavigationServiceTest {
    private static final String VIN = "5YJ3E1EA7JF000000";
    private static final String USER = "tap_subject_123";
    private static final TapAccessService.GrantIdentity IDENTITY = identity(USER);
    private static final TapAccessService.GrantIdentity OTHER_IDENTITY = identity("tap_subject_other");
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    private TapVehicleClient vehicleClient;
    private GeocodingClient geocodingClient;
    private Path sessionsDirectory;
    private AutoNavigationService service;

    @BeforeEach
    void setUp() throws IOException {
        vehicleClient = mock(TapVehicleClient.class);
        geocodingClient = mock(GeocodingClient.class);
        sessionsDirectory = tempDir.resolve("sessions");
        service = newService(sessionsDirectory);
        when(vehicleClient.sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString()))
                .thenReturn(acceptedCommand());
    }

    @Test
    void grantIdentityIsOmittedFromPublicSessionJsonButStoredForRestart() throws IOException {
        AutoNavSession session = service.createSession(VIN, IDENTITY, addresses(1));
        ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        assertFalse(mapper.valueToTree(session).has("grantId"));
        assertEquals(IDENTITY.grantId(), mapper.readTree(Files.readString(sessionFile(session)))
                .path("grantId").asString());
    }

    @Test
    void sendsInitialBatchOnlyFromFreshParkedBaseline() {
        AutoNavSession session = startSession(2);
        Telemetry snapshot = telemetry(1, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), snapshot, 1, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
        assertEquals(0, session.getCompletedAddresses(), "sending a route does not count as arrival");
        assertEquals(0, session.getCurrentWaypointIndex());
        verify(vehicleClient).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
        assertNotNull(session.getPendingCommandIdempotencyKey());
    }

    @Test
    void staleOrMissingTelemetryCannotStartRoute() {
        AutoNavSession staleSession = startSession(1);
        Telemetry stale = telemetry(1, "P", 0, NOW.minusSeconds(120), NOW.minusSeconds(120));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), stale, 1, false, false, NOW.minusSeconds(120)));

        service.pollRunningSessions();

        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
        assertEquals(AutoNavSession.Status.RUNNING, staleSession.getStatus());
        assertEquals(0, staleSession.getCompletedAddresses());

        AutoNavSession missingSession = service.createSession("5YJ3E1EA7JF000001", OTHER_IDENTITY, addresses(1));
        service.startSession(missingSession.getSessionId(), OTHER_IDENTITY);
        Telemetry missingShiftTime = new Telemetry(
                1, NOW.minusSeconds(1), NOW.minusSeconds(1), 42.0, -83.0,
                "P", 0.0, NOW.minusSeconds(1), null, NOW.minusSeconds(1));
        when(vehicleClient.telemetry(eq(OTHER_IDENTITY), eq("5YJ3E1EA7JF000001"), isNull()))
                .thenReturn(new TelemetryPage(List.of(), missingShiftTime, 1, false, false, NOW));

        service.pollRunningSessions();

        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
        assertEquals(AutoNavSession.Status.RUNNING, missingSession.getStatus());
        assertEquals(0, missingSession.getCompletedAddresses());
    }

    @Test
    void telemetryGapFailsClosedWithoutSending() {
        AutoNavSession session = startSession(1);
        Telemetry snapshot = telemetry(4, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), snapshot, 4, false, true, NOW));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertTrue(session.getLastError().contains("gap"));
        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
    }

    @Test
    void onlyFreshOrderedParkTransitionAtWaypointAdvancesProgress() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        List<Telemetry> events = List.of(
                event(2, "D", 0, NOW.minusSeconds(8)),
                event(3, "P", 0, NOW.minusSeconds(7)),
                event(4, "P", 0, NOW.minusSeconds(6)));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(events, null, 4, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(1, session.getCompletedAddresses(), "a repeated P event must not count twice");
        assertEquals(1, session.getCurrentWaypointIndex());
        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void staleArrivalEventsDoNotAdvanceWaypoint() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant stale = NOW.minusSeconds(120);
        List<Telemetry> events = List.of(event(2, "D", 0, stale), event(3, "P", 0, stale.plusSeconds(1)));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(events, null, 3, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(0, session.getCompletedAddresses());
        assertEquals(0, session.getCurrentWaypointIndex());
        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
    }

    @Test
    void hasMoreTelemetryDefersNextBatchUntilCurrentPostParkLocation() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> arrivals = arrivalEvents(8, 2, NOW.minusSeconds(50));
        Telemetry lastPark = arrivals.get(arrivals.size() - 1);
        long cursor = lastPark.cursor();
        Telemetry boundary = boundarySnapshot(7, lastPark.shiftStateObservedAt());

        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(arrivals, null, cursor, true, false, NOW));
        service.pollRunningSessions();

        assertEquals(8, session.getCompletedAddresses());
        assertEquals(1, session.getCurrentGroupIndex());
        assertTrue(session.isNextGroupReady());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());

        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(cursor)))
                .thenReturn(new TelemetryPage(List.of(), null, cursor, false, false, NOW));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), boundary, cursor, false, false, NOW));
        service.pollRunningSessions();

        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
        assertEquals(1, session.getLastGroupSize());
        verify(vehicleClient, times(2)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void newerCursorOnSeparateSnapshotReadDefersBatchWithoutSkippingReplay() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> arrivals = arrivalEvents(8, 2, NOW.minusSeconds(50));
        long cursor = arrivals.get(arrivals.size() - 1).cursor();
        Telemetry boundary = boundarySnapshot(7, arrivals.get(arrivals.size() - 1).shiftStateObservedAt());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(arrivals, null, cursor, false, false, NOW));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), boundary, cursor + 1, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(8, session.getCompletedAddresses());
        assertEquals(cursor, session.getTelemetryCursor());
        assertEquals(AutoNavSession.CommandState.NONE, session.getCommandState());
        assertTrue(session.getLastError().contains("waiting for replay"));
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
        verify(vehicleClient, times(2)).telemetry(eq(IDENTITY), eq(VIN), isNull());
    }

    @Test
    void oldLocationCannotConfirmParkButLaterLocationConfirmsOnlyOnce() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant parkedAt = NOW.minusSeconds(8);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(event(2, "D", 0, NOW.minusSeconds(9)),
                        telemetry(3, "P", 0, NOW.minusSeconds(50), parkedAt)), null, 3, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(0, session.getCompletedAddresses());
        assertEquals(parkedAt, session.getPendingParkTransitionAt());

        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(3L)))
                .thenReturn(new TelemetryPage(List.of(
                        telemetry(4, "P", 0, NOW.minusSeconds(6), parkedAt),
                        telemetry(5, "P", 1, NOW.minusSeconds(5), parkedAt)), null, 5, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(1, session.getCompletedAddresses(), "one Park transition cannot complete two stops");
        assertNull(session.getPendingParkTransitionAt());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void departureBeforeLocationConfirmationCancelsPendingPark() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant parkedAt = NOW.minusSeconds(8);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(event(2, "D", 0, NOW.minusSeconds(9)),
                        telemetry(3, "P", 0, NOW.minusSeconds(50), parkedAt),
                        event(4, "D", 1, NOW.minusSeconds(7)),
                        telemetry(5, "P", 0, NOW.minusSeconds(6), parkedAt)), null, 5, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(0, session.getCompletedAddresses());
        assertNull(session.getPendingParkTransitionAt());
    }

    @Test
    void lateDeliveredArrivalBeforeOrAtCommandSubmissionCannotCount() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        session.setCommandSubmittedAt(NOW);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(event(2, "D", 0, NOW.minusSeconds(9)),
                        event(3, "P", 0, NOW.minusSeconds(8))), null, 3, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(0, session.getCompletedAddresses());
        assertNull(session.getPendingParkTransitionAt());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(3L)))
                .thenReturn(new TelemetryPage(List.of(event(4, "D", 0, NOW.minusSeconds(1)),
                        event(5, "P", 0, NOW)), null, 5, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(0, session.getCompletedAddresses(), "equal source time cannot establish a post-command arrival");
        assertNull(session.getPendingParkTransitionAt());
    }

    @Test
    void nearPreParkLocationCanConfirmArrival() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant parkedAt = NOW.minusSeconds(8);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(
                        event(2, "D", 0, NOW.minusSeconds(9)),
                        telemetry(3, "P", 0, parkedAt.minusSeconds(2), parkedAt)),
                        null, 3, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(1, session.getCompletedAddresses());
        assertNull(session.getPendingParkTransitionAt());
    }

    @Test
    void locationFiftySecondsBeforeParkDoesNotConfirmArrival() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant parkedAt = NOW.minusSeconds(8);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(
                        event(2, "D", 0, NOW.minusSeconds(9)),
                        telemetry(3, "P", 0, parkedAt.minusSeconds(50), parkedAt)),
                        null, 3, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(0, session.getCompletedAddresses());
        assertEquals(parkedAt, session.getPendingParkTransitionAt());
    }

    @Test
    void locationOnlyUpdateCanConfirmUsingHeldParkEvidence() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        Instant parkedAt = NOW.minusSeconds(8);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(
                        event(2, "D", 0, NOW.minusSeconds(9)),
                        telemetry(3, "P", 0, parkedAt.minusSeconds(50), parkedAt)),
                        null, 3, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(0, session.getCompletedAddresses());

        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(3L)))
                .thenReturn(new TelemetryPage(List.of(locationOnlyTelemetry(4, 0, NOW.minusSeconds(6))),
                        null, 4, false, false, NOW));
        service.pollRunningSessions();

        assertEquals(1, session.getCompletedAddresses());
        assertNull(session.getPendingParkTransitionAt());
    }

    @Test
    void locationObservedBeforeCommandCannotConfirmLaterPark() {
        AutoNavSession session = startSession(2);
        sendInitialBatch(session);
        session.setCommandSubmittedAt(NOW.minusSeconds(8));
        Instant parkedAt = NOW.minusSeconds(5);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(
                        event(2, "D", 0, NOW.minusSeconds(7)),
                        telemetry(3, "P", 0, NOW.minusSeconds(9), parkedAt)),
                        null, 3, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(0, session.getCompletedAddresses());
        assertEquals(parkedAt, session.getPendingParkTransitionAt());
    }

    @Test
    void boundarySnapshotWithPreParkLocationCannotSendNextBatch() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> arrivals = arrivalEvents(8, 2, NOW.minusSeconds(50));
        Telemetry lastPark = arrivals.get(arrivals.size() - 1);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(arrivals, null, lastPark.cursor(), false, false, NOW));
        Telemetry oldLocation = telemetry(lastPark.cursor(), "P", 7,
                lastPark.shiftStateObservedAt().minusSeconds(11), lastPark.shiftStateObservedAt());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), oldLocation, lastPark.cursor(), false, false, NOW));
        service.pollRunningSessions();
        assertEquals(8, session.getCompletedAddresses());
        assertEquals(AutoNavSession.CommandState.NONE, session.getCommandState());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void boundarySnapshotWithLocationTwoSecondsBeforeParkCanSendNextBatch() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> arrivals = arrivalEvents(8, 2, NOW.minusSeconds(50));
        Telemetry lastPark = arrivals.get(arrivals.size() - 1);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(arrivals, null, lastPark.cursor(), false, false, NOW));
        Telemetry nearPreParkLocation = telemetry(lastPark.cursor(), "P", 7,
                lastPark.shiftStateObservedAt().minusSeconds(2), lastPark.shiftStateObservedAt());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), nearPreParkLocation, lastPark.cursor(), false, false, NOW));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
        verify(vehicleClient, times(2)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void leavingParkAfterBatchBoundaryFailsBeforeSendingNextBatch() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> events = new ArrayList<>(arrivalEvents(8, 2, NOW.minusSeconds(50)));
        Instant departureAt = events.get(events.size() - 1).shiftStateObservedAt().plusSeconds(1);
        events.add(event(events.size() + 2L, "D", 7, departureAt));
        long cursor = events.get(events.size() - 1).cursor();
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(events, null, cursor, false, false, NOW));

        service.pollRunningSessions();

        assertEquals(8, session.getCompletedAddresses());
        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertTrue(session.getLastError().contains("confirmed waypoint"), session.getLastError());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void finalBatchCompletesOnlyAfterFinalWaypointArrival() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        List<Telemetry> firstEight = arrivalEvents(8, 2, NOW.minusSeconds(50));
        long cursor = firstEight.get(firstEight.size() - 1).cursor();
        Telemetry boundary = boundarySnapshot(7, firstEight.get(firstEight.size() - 1).shiftStateObservedAt());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(firstEight, null, cursor, false, false, NOW));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(), boundary, cursor, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(8, session.getCompletedAddresses());
        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());

        Instant departureAt = NOW.minusSeconds(8);
        session.setCommandSubmittedAt(departureAt.minusSeconds(2));
        List<Telemetry> finalArrival = List.of(
                event(cursor + 1, "D", 8, departureAt),
                event(cursor + 2, "P", 8, departureAt.plusSeconds(1)));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(cursor)))
                .thenReturn(new TelemetryPage(finalArrival, null, cursor + 2, false, false, NOW));
        service.pollRunningSessions();

        assertEquals(9, session.getCompletedAddresses());
        assertEquals(AutoNavSession.Status.COMPLETED, session.getStatus());
        verify(vehicleClient, times(2)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void ambiguousCommandResultIsRecordedAndNeverRetried() {
        AutoNavSession session = startSession(1);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(),
                        telemetry(1, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2)),
                        1, false, false, NOW));
        when(vehicleClient.sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString()))
                .thenReturn(new CommandResult("PENDING", null, "still processing"));

        service.pollRunningSessions();
        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertEquals(AutoNavSession.CommandState.UNKNOWN, session.getCommandState());
        assertTrue(session.getLastError().contains("not be retried"));
        assertNotNull(session.getPendingCommandIdempotencyKey());
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
        assertThrows(IllegalStateException.class, () -> service.startSession(session.getSessionId()));
    }

    @Test
    void activeSessionLocksVinAgainstNewSessionsAndManualRoutes() {
        AutoNavSession session = startSession(1);

        assertThrows(IllegalStateException.class, () -> service.createSession(VIN, IDENTITY, addresses(1)));
        assertThrows(IllegalStateException.class,
                () -> service.sendManualRoute(USER, VIN, addresses(1), "a".repeat(36)));

        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
        assertEquals(AutoNavSession.Status.RUNNING, session.getStatus());
    }

    @Test
    void onlyOneBlockingSessionPerOwnerAcrossVehicles() {
        service.createSession(VIN, IDENTITY, addresses(1));

        assertThrows(IllegalStateException.class,
                () -> service.createSession("5YJ3E1EA7JF000001", IDENTITY, addresses(1)));
    }

    @Test
    void concurrentCreatesForOneOwnerAreSerialized() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> createAfterGate(start, VIN));
            Future<Boolean> second = executor.submit(() -> createAfterGate(start, "5YJ3E1EA7JF000001"));
            start.countDown();

            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cannotResumeSecondPersistedSessionForSameOwner() throws IOException {
        AutoNavSession active = startSession(1);
        AutoNavSession hidden = persistedSession("123e4567-e89b-12d3-a456-426614174001",
                AutoNavSession.Status.PAUSED, AutoNavSession.CommandState.NONE);
        hidden.setVin("5YJ3E1EA7JF000001");
        writeSession(hidden);

        assertNotNull(service.getSession(hidden.getSessionId()));
        assertThrows(IllegalStateException.class, () -> service.startSession(hidden.getSessionId()));
        assertEquals(AutoNavSession.Status.RUNNING, active.getStatus());
    }

    @Test
    void rejectsInvalidSessionIdentifiersBeforeLoading() {
        assertNull(service.getSession("../../README.md"));
        assertNull(service.getSession("not-a-uuid"));
    }

    @Test
    void rejectsNonFiniteArrivalRadius() {
        assertThrows(IllegalArgumentException.class,
                () -> new AutoNavigationService(vehicleClient, geocodingClient, sessionsDirectory, CLOCK, Double.NaN, 60));
        assertThrows(IllegalArgumentException.class,
                () -> new AutoNavigationService(vehicleClient, geocodingClient, sessionsDirectory, CLOCK,
                        Double.POSITIVE_INFINITY, 60));
    }

    @Test
    void cleanupPreservesOldRunningAndAmbiguousSessions() {
        AutoNavSession running = service.createSession(VIN, IDENTITY, addresses(1));
        running.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(25));
        service.startSession(running.getSessionId());

        AutoNavSession ambiguous = service.createSession("5YJ3E1EA7JF000001", OTHER_IDENTITY, addresses(1));
        ambiguous.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(25));
        ambiguous.setCommandState(AutoNavSession.CommandState.UNKNOWN);
        ambiguous.setStatus(AutoNavSession.Status.ERROR);

        service.cleanupOldSessions();

        assertSame(running, service.getSession(running.getSessionId()));
        assertSame(ambiguous, service.getSession(ambiguous.getSessionId()));
        assertTrue(Files.exists(sessionFile(running)));
        assertTrue(Files.exists(sessionFile(ambiguous)));
    }

    @Test
    void cleanupRemovesOnlyOldResolvedTerminalSessions() throws IOException {
        AutoNavSession stopped = service.createSession(VIN, IDENTITY, addresses(1));
        stopped.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(25));
        service.stopSession(stopped.getSessionId());

        service.cleanupOldSessions();

        assertNull(service.getSession(stopped.getSessionId()));
        assertFalse(Files.exists(sessionFile(stopped)));
    }

    @Test
    void restartPausesAcceptedCommandAndDoesNotDuplicateIt() throws IOException {
        AutoNavSession persisted = persistedSession("123e4567-e89b-12d3-a456-426614174000", AutoNavSession.Status.RUNNING,
                AutoNavSession.CommandState.ACCEPTED);
        persisted.setTelemetryCursor(1L);
        persisted.setPendingParkTransitionAt(NOW.minusSeconds(2));
        writeSession(persisted);
        AutoNavigationService restarted = newService(sessionsDirectory);
        AutoNavSession restored = restarted.getSession(persisted.getSessionId());

        assertEquals(AutoNavSession.Status.PAUSED, restored.getStatus());
        assertEquals(AutoNavSession.CommandState.ACCEPTED, restored.getCommandState());
        assertEquals(1, restored.getAllAddresses().size(), "computed collection must not append persisted addresses");
        assertNull(restored.getPendingParkTransitionAt(), "a restart must discard unconfirmed arrival evidence");

        restarted.startSession(restored.getSessionId());
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(1L)))
                .thenReturn(new TelemetryPage(List.of(),
                        telemetry(1, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2)),
                        1, false, false, NOW));
        restarted.pollRunningSessions();

        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
    }

    @Test
    void restartWithUnknownOrLegacyCommandStateRequiresManualReview() throws IOException {
        AutoNavSession interrupted = persistedSession("123e4567-e89b-12d3-a456-426614174002", AutoNavSession.Status.RUNNING,
                AutoNavSession.CommandState.SUBMITTING);
        interrupted.setPendingCommandIdempotencyKey("a".repeat(36));
        writeSession(interrupted);
        AutoNavSession legacy = persistedSession("123e4567-e89b-12d3-a456-426614174003", AutoNavSession.Status.RUNNING, null);
        writeSession(legacy);

        AutoNavigationService restarted = newService(sessionsDirectory);
        AutoNavSession restoredUnknown = restarted.getSession(interrupted.getSessionId());
        AutoNavSession restoredLegacy = restarted.getSession(legacy.getSessionId());

        assertEquals(AutoNavSession.Status.ERROR, restoredUnknown.getStatus());
        assertEquals(AutoNavSession.CommandState.UNKNOWN, restoredUnknown.getCommandState());
        assertEquals(AutoNavSession.Status.ERROR, restoredLegacy.getStatus());
        assertNull(restoredLegacy.getCommandState());
        assertThrows(IllegalStateException.class, () -> restarted.startSession(restoredUnknown.getSessionId()));
        assertThrows(IllegalStateException.class, () -> restarted.startSession(restoredLegacy.getSessionId()));
        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
    }

    @Test
    void telemetryAuthorizationDenialStopsPollingAndClearsPendingArrival() {
        AutoNavSession session = startSession(1);
        session.setPendingParkTransitionAt(NOW.minusSeconds(1));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenThrow(new AccessDeniedException("TAP access expired"));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertNull(session.getPendingParkTransitionAt());
        service.pollRunningSessions();
        verify(vehicleClient, times(1)).telemetry(eq(IDENTITY), eq(VIN), isNull());
        verify(vehicleClient, never()).sendRoute(any(TapAccessService.GrantIdentity.class), anyString(), anyList(), anyString());
    }

    @Test
    void boundarySnapshotAuthorizationDenialStopsPollingWithoutRetry() {
        AutoNavSession session = startSession(9);
        sendInitialBatch(session);
        long cursor = session.getTelemetryCursor();
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), eq(cursor)))
                .thenReturn(new TelemetryPage(arrivalEvents(8, cursor + 1, NOW.minusSeconds(50)),
                        null, cursor + 16, false, false, NOW));
        doAnswer(invocation -> {
            session.setPendingParkTransitionAt(NOW.minusSeconds(1));
            throw new AccessDeniedException("TAP access expired");
        }).when(vehicleClient).telemetry(eq(IDENTITY), eq(VIN), isNull());

        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertNull(session.getPendingParkTransitionAt());
        verify(vehicleClient, times(1)).telemetry(eq(IDENTITY), eq(VIN), eq(cursor));
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
        service.pollRunningSessions();
        verify(vehicleClient, times(1)).telemetry(eq(IDENTITY), eq(VIN), eq(cursor));
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void commandAuthorizationDenialIsRejectedAndNeverRetried() {
        AutoNavSession session = startSession(1);
        session.setPendingParkTransitionAt(NOW.minusSeconds(1));
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(),
                        telemetry(1, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2)),
                        1, false, false, NOW));
        when(vehicleClient.sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString()))
                .thenThrow(new AccessDeniedException("TAP denied route"));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertEquals(AutoNavSession.CommandState.REJECTED, session.getCommandState());
        assertNotNull(session.getPendingCommandIdempotencyKey());
        assertNull(session.getPendingParkTransitionAt());
        service.pollRunningSessions();
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void routeTransportFailureRemainsUnknownAndIsNeverRetried() {
        AutoNavSession session = startSession(1);
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(),
                        telemetry(1, "P", 0, NOW.minusSeconds(2), NOW.minusSeconds(2)),
                        1, false, false, NOW));
        when(vehicleClient.sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString()))
                .thenThrow(new IllegalStateException("connection reset"));

        service.pollRunningSessions();

        assertEquals(AutoNavSession.Status.ERROR, session.getStatus());
        assertEquals(AutoNavSession.CommandState.UNKNOWN, session.getCommandState());
        assertNotNull(session.getPendingCommandIdempotencyKey());
        service.pollRunningSessions();
        verify(vehicleClient, times(1)).sendRoute(eq(IDENTITY), eq(VIN), anyList(), anyString());
    }

    @Test
    void tapSubjectOwnershipIsCaseSensitiveAndCannotBypassVehicleLock() {
        String subject = "Tap-Subject-A";
        AutoNavSession session = service.createSession(VIN, identity(subject), addresses(1));

        assertNull(service.getActiveSessionForUser(identity(subject.toLowerCase())));
        assertThrows(IllegalStateException.class,
                () -> service.createSession(VIN, identity(subject.toLowerCase()), addresses(1)));
        assertThrows(IllegalStateException.class,
                () -> service.sendManualRoute(identity(subject.toLowerCase()), VIN, addresses(1), "b".repeat(36)));
        assertEquals(subject, session.getUserId());
    }

    @Test
    void ownerMigrationPreservesLoadedSessionStateAndBacksUpOriginalJson() throws IOException {
        String legacyOwner = "driver@example.com";
        AutoNavSession persisted = new AutoNavSession(
                "123e4567-e89b-12d3-a456-426614174010", VIN, legacyOwner, addresses(9));
        persisted.setStatus(AutoNavSession.Status.ERROR);
        persisted.setCommandState(AutoNavSession.CommandState.UNKNOWN);
        persisted.setCurrentGroupIndex(1);
        persisted.setCurrentWaypointIndex(8);
        persisted.setCompletedAddresses(8);
        persisted.setTelemetryCursor(73L);
        persisted.setPendingCommandIdempotencyKey("a".repeat(36));
        persisted.setCommandSubmittedAt(NOW.minusSeconds(12));
        persisted.setLastError("manual review required");
        writeSession(persisted);

        AutoNavSession unrelated = persistedSession(
                "123e4567-e89b-12d3-a456-426614174011", AutoNavSession.Status.PAUSED,
                AutoNavSession.CommandState.NONE);
        unrelated.setUserId("someone@example.com");
        writeSession(unrelated);

        AutoNavigationService restarted = newService(sessionsDirectory);
        AutoNavSession loaded = restarted.getSession(persisted.getSessionId());
        restarted.migrateOwner("DRIVER@example.com", "Tap-Subject-A");

        assertEquals(persisted.getSessionId(), loaded.getSessionId());
        assertEquals("Tap-Subject-A", loaded.getUserId());
        assertEquals(AutoNavSession.Status.ERROR, loaded.getStatus());
        assertEquals(AutoNavSession.CommandState.UNKNOWN, loaded.getCommandState());
        assertEquals(1, loaded.getCurrentGroupIndex());
        assertEquals(8, loaded.getCurrentWaypointIndex());
        assertEquals(8, loaded.getCompletedAddresses());
        assertEquals(73L, loaded.getTelemetryCursor());
        assertEquals("a".repeat(36), loaded.getPendingCommandIdempotencyKey());
        assertEquals(NOW.minusSeconds(12), loaded.getCommandSubmittedAt());
        assertEquals("someone@example.com", restarted.getSession(unrelated.getSessionId()).getUserId());
        assertThrows(IllegalStateException.class, () -> restarted.startSession(loaded.getSessionId()));

        Path backup;
        try (var files = Files.list(sessionsDirectory)) {
            backup = files.filter(path -> path.getFileName().toString()
                            .startsWith(persisted.getSessionId() + ".json.owner-migration-"))
                    .filter(path -> path.getFileName().toString().endsWith(".bak"))
                    .findFirst()
                    .orElseThrow();
        }
        assertTrue(Files.readString(backup).contains(legacyOwner));
    }

    @Test
    void ownerMigrationRefusesABlockingTargetOwnerWithoutChangingEitherOwner() {
        AutoNavSession legacy = service.createSession(VIN, "driver@example.com", addresses(1));
        AutoNavSession target = service.createSession("5YJ3E1EA7JF000001", "Tap-Subject-A", addresses(1));

        assertThrows(IllegalStateException.class,
                () -> service.migrateOwner("driver@example.com", "Tap-Subject-A"));

        assertEquals("driver@example.com", legacy.getUserId());
        assertEquals("Tap-Subject-A", target.getUserId());
    }

    private AutoNavigationService newService(Path directory) {
        return new AutoNavigationService(vehicleClient, geocodingClient, directory, CLOCK, 75, 60);
    }

    private boolean createAfterGate(CountDownLatch start, String vin) throws InterruptedException {
        start.await();
        try {
            service.createSession(vin, USER, addresses(1));
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private AutoNavSession startSession(int count) {
        AutoNavSession session = service.createSession(VIN, IDENTITY, addresses(count));
        return service.startSession(session.getSessionId(), IDENTITY);
    }

    private static TapAccessService.GrantIdentity identity(String ownerSub) {
        return new TapAccessService.GrantIdentity(ownerSub, "a".repeat(64));
    }

    private void sendInitialBatch(AutoNavSession session) {
        when(vehicleClient.telemetry(eq(IDENTITY), eq(VIN), isNull()))
                .thenReturn(new TelemetryPage(List.of(),
                        telemetry(1, "P", 0, NOW.minusSeconds(55), NOW.minusSeconds(55)),
                        1, false, false, NOW));
        service.pollRunningSessions();
        assertEquals(AutoNavSession.CommandState.ACCEPTED, session.getCommandState());
        // Subsequent fixed-clock fixture events occur after this synthetic submission.
        session.setCommandSubmittedAt(NOW.minusSeconds(54));
    }

    private CommandResult acceptedCommand() {
        return new CommandResult("COMPLETED", Boolean.TRUE, "accepted");
    }

    private Telemetry telemetry(long cursor, String shift, int addressIndex, Instant locationAt, Instant shiftAt) {
        PlaceCandidate place = addresses(Math.max(addressIndex + 1, 1)).get(addressIndex);
        Instant envelopeAt = NOW.minusSeconds(1);
        return new Telemetry(cursor, envelopeAt, envelopeAt, place.lat(), place.lon(),
                shift, 0.0, locationAt, shiftAt, envelopeAt);
    }

    private Telemetry locationOnlyTelemetry(long cursor, int addressIndex, Instant locationAt) {
        PlaceCandidate place = addresses(Math.max(addressIndex + 1, 1)).get(addressIndex);
        Instant envelopeAt = NOW.minusSeconds(1);
        return new Telemetry(cursor, envelopeAt, envelopeAt, place.lat(), place.lon(),
                null, 0.0, locationAt, null, envelopeAt);
    }

    private Telemetry event(long cursor, String shift, int addressIndex, Instant observedAt) {
        PlaceCandidate place = addresses(Math.max(addressIndex + 1, 1)).get(addressIndex);
        return new Telemetry(cursor, observedAt, observedAt, place.lat(), place.lon(),
                shift, 0.0, observedAt, observedAt, observedAt);
    }

    private List<Telemetry> arrivalEvents(int count, long firstCursor, Instant firstAt) {
        List<Telemetry> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant departureAt = firstAt.plusSeconds(i * 4L);
            events.add(event(firstCursor + events.size(), "D", i, departureAt));
            events.add(event(firstCursor + events.size(), "P", i, departureAt.plusSeconds(1)));
        }
        return events;
    }

    private Telemetry boundarySnapshot(int addressIndex, Instant lastParkAt) {
        return telemetry(100, "P", addressIndex, lastParkAt.plusSeconds(1), lastParkAt.plusSeconds(2));
    }

    private List<PlaceCandidate> addresses(int count) {
        List<PlaceCandidate> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new PlaceCandidate("Address " + i, "ADDRESS " + i, "image.png", i,
                    42.0 + i * 0.001, -83.0, "place-" + i));
        }
        return result;
    }

    private Path sessionFile(AutoNavSession session) {
        return sessionsDirectory.resolve(session.getSessionId() + ".json");
    }

    private AutoNavSession persistedSession(String id, AutoNavSession.Status status,
                                           AutoNavSession.CommandState commandState) {
        AutoNavSession session = new AutoNavSession(id, VIN, USER, addresses(1));
        session.setStatus(status);
        session.setCommandState(commandState);
        return session;
    }

    private void writeSession(AutoNavSession session) throws IOException {
        ObjectMapper mapper = JsonMapper.builderWithJackson2Defaults()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        Files.createDirectories(sessionsDirectory);
        mapper.writeValue(sessionFile(session).toFile(), session);
    }
}
