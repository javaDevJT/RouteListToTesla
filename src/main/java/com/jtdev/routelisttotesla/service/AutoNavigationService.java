package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.service.TapVehicleClient.CommandResult;
import com.jtdev.routelisttotesla.service.TapVehicleClient.Telemetry;
import com.jtdev.routelisttotesla.service.TapVehicleClient.TelemetryPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class AutoNavigationService {

    private static final Logger log = LoggerFactory.getLogger(AutoNavigationService.class);
    private static final String SESSIONS_DIR = "cache/auto-nav-sessions";
    private static final int MAX_SESSION_AGE_HOURS = 24;
    private static final long PARK_LOCATION_EARLY_TOLERANCE_SECONDS = 10;

    private final TapVehicleClient tapVehicleClient;
    private final GeocodingClient geocodingClient;
    private final ObjectMapper objectMapper;
    private final Path sessionsDirectory;
    private final Clock clock;
    private final double arrivalRadiusMeters;
    private final long maxTelemetryAgeSeconds;
    private final ConcurrentHashMap<String, AutoNavSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> vehicleLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> userLocks = new ConcurrentHashMap<>();

    @Autowired
    public AutoNavigationService(
            TapVehicleClient tapVehicleClient,
            GeocodingClient geocodingClient,
            @Value("${navigation.arrival-radius-meters:75}") double arrivalRadiusMeters,
            @Value("${navigation.max-telemetry-age-seconds:60}") long maxTelemetryAgeSeconds) {
        this(tapVehicleClient, geocodingClient, Paths.get(SESSIONS_DIR), Clock.systemUTC(),
                arrivalRadiusMeters, maxTelemetryAgeSeconds);
    }

    AutoNavigationService(
            TapVehicleClient tapVehicleClient,
            GeocodingClient geocodingClient,
            Path sessionsDirectory,
            Clock clock,
            double arrivalRadiusMeters,
            long maxTelemetryAgeSeconds) {
        if (!Double.isFinite(arrivalRadiusMeters) || arrivalRadiusMeters <= 0 || maxTelemetryAgeSeconds <= 0) {
            throw new IllegalArgumentException("Navigation freshness and arrival radius must be positive");
        }
        this.tapVehicleClient = tapVehicleClient;
        this.geocodingClient = geocodingClient;
        this.sessionsDirectory = sessionsDirectory;
        this.clock = clock;
        this.arrivalRadiusMeters = arrivalRadiusMeters;
        this.maxTelemetryAgeSeconds = maxTelemetryAgeSeconds;
        this.objectMapper = JsonMapper.builderWithJackson2Defaults()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        initializeSessionsDirectory();
        loadPersistedSessions();
    }

    AutoNavSession createSession(String vin, String userId, List<PlaceCandidate> addresses) {
        if (vin == null || vin.isBlank() || userId == null || userId.isBlank()
                || addresses == null || addresses.isEmpty()) {
            throw new IllegalArgumentException("A vehicle, owner, and at least one waypoint are required");
        }
        synchronized (userLock(userId)) {
            synchronized (vehicleLock(vin)) {
                if (hasBlockingSessionForUser(userId)) {
                    throw new IllegalStateException("An auto-navigation session already owns this user account");
                }
                if (hasBlockingSessionForVin(vin)) {
                    throw new IllegalStateException("A navigation session already owns this vehicle; stop it before creating another");
                }
                String sessionId = UUID.randomUUID().toString();
                AutoNavSession session = new AutoNavSession(sessionId, vin, userId, addresses);
                activeSessions.put(sessionId, session);
                try {
                    persistSession(session);
                } catch (RuntimeException e) {
                    activeSessions.remove(sessionId, session);
                    throw e;
                }
                log.info("Created auto-navigation session {} for VIN {} with {} addresses", sessionId, vin, addresses.size());
                return session;
            }
        }
    }

    public AutoNavSession createSession(String vin, TapAccessService.GrantIdentity identity,
                                        List<PlaceCandidate> addresses) {
        if (identity == null) throw new AccessDeniedException("Sign in through TAP first");
        AutoNavSession session = createSession(vin, identity.ownerSub(), addresses);
        session.setGrantId(identity.grantId());
        persistSession(session);
        return session;
    }

    AutoNavSession startSession(String sessionId) {
        AutoNavSession session = getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        synchronized (userLock(session.getUserId())) {
            if (hasOtherBlockingSessionForUser(session.getUserId(), sessionId)) {
                throw new IllegalStateException("An auto-navigation session already owns this user account");
            }
            return startSessionForVehicle(sessionId, session);
        }
    }

    public AutoNavSession startSession(String sessionId, TapAccessService.GrantIdentity identity) {
        AutoNavSession session = requireOwnedGrant(sessionId, identity);
        return startSessionForVehicle(sessionId, session);
    }

    private AutoNavSession startSessionForVehicle(String sessionId, AutoNavSession session) {
        synchronized (vehicleLock(session.getVin())) {
            if (hasOtherBlockingSessionForVin(session.getVin(), sessionId)) {
                throw new IllegalStateException("Another navigation session already owns this vehicle");
            }
            if (session.getStatus() == AutoNavSession.Status.RUNNING) return session;
            if (session.getCommandState() == null) {
                failSession(session, "This saved session predates telemetry progress tracking; stop it and create a new route.");
                throw new IllegalStateException(session.getLastError());
            }
            if (session.getCommandState() == AutoNavSession.CommandState.SUBMITTING
                    || session.getCommandState() == AutoNavSession.CommandState.UNKNOWN
                    || session.getCommandState() == AutoNavSession.CommandState.REJECTED) {
                throw new IllegalStateException("The last route command needs manual review; it will not be retried");
            }
            if (session.getStatus() != AutoNavSession.Status.CREATED
                    && session.getStatus() != AutoNavSession.Status.PAUSED) {
                throw new IllegalStateException("Session cannot be started from status " + session.getStatus());
            }
            session.setStatus(AutoNavSession.Status.RUNNING);
            session.setLastError(null);
            persistSession(session);
            return session;
        }
    }

    AutoNavSession stopSession(String sessionId) {
        AutoNavSession session = getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        synchronized (vehicleLock(session.getVin())) {
            session.setStatus(AutoNavSession.Status.STOPPED);
            persistSession(session);
            log.info("Stopped auto-navigation session {}", sessionId);
            return session;
        }
    }

    public AutoNavSession stopSession(String sessionId, TapAccessService.GrantIdentity identity) {
        AutoNavSession session = requireOwnedGrant(sessionId, identity);
        synchronized (vehicleLock(session.getVin())) {
            session.setStatus(AutoNavSession.Status.STOPPED);
            persistSession(session);
            log.info("Stopped auto-navigation session {}", sessionId);
            return session;
        }
    }

    AutoNavSession getSession(String sessionId) {
        if (!isValidSessionId(sessionId)) return null;
        AutoNavSession session = activeSessions.get(sessionId);
        if (session != null) return session;
        session = loadSessionFromDisk(sessionId);
        if (session == null) return null;
        AutoNavSession previous = activeSessions.putIfAbsent(sessionId, session);
        return previous == null ? session : previous;
    }

    public AutoNavSession getSession(String sessionId, TapAccessService.GrantIdentity identity) {
        return requireOwnedGrant(sessionId, identity);
    }

    private AutoNavSession requireOwnedGrant(String sessionId, TapAccessService.GrantIdentity identity) {
        AutoNavSession session = getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("Session not found: " + sessionId);
        if (identity == null || !identity.ownerSub().equals(session.getUserId())
                || !identity.grantId().equals(session.getGrantId())) {
            throw new AccessDeniedException("This navigation session belongs to a different TAP grant");
        }
        return session;
    }

    private TapAccessService.GrantIdentity grantIdentity(AutoNavSession session) {
        try {
            return new TapAccessService.GrantIdentity(session.getUserId(), session.getGrantId());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("This saved navigation session is not bound to a TAP grant");
        }
    }

    private boolean isValidSessionId(String sessionId) {
        if (sessionId == null) return false;
        try {
            return UUID.fromString(sessionId).toString().equalsIgnoreCase(sessionId);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    AutoNavSession getActiveSessionForUser(String userId) {
        return activeSessions.values().stream()
                .filter(session -> userId.equals(session.getUserId()))
                .filter(session -> session.getStatus() == AutoNavSession.Status.RUNNING
                        || session.getStatus() == AutoNavSession.Status.CREATED
                        || session.getStatus() == AutoNavSession.Status.PAUSED
                        || session.getStatus() == AutoNavSession.Status.ERROR)
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);
    }

    public AutoNavSession getActiveSessionForUser(TapAccessService.GrantIdentity identity) {
        if (identity == null) return null;
        return activeSessions.values().stream()
                .filter(session -> identity.ownerSub().equals(session.getUserId()))
                .filter(session -> identity.grantId().equals(session.getGrantId()))
                .filter(session -> session.getStatus() == AutoNavSession.Status.RUNNING
                        || session.getStatus() == AutoNavSession.Status.CREATED
                        || session.getStatus() == AutoNavSession.Status.PAUSED
                        || session.getStatus() == AutoNavSession.Status.ERROR)
                .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .orElse(null);
    }

    /** Serializes manual sends with session creation, polling, and stop for the same VIN. */
    synchronized CommandResult sendManualRoute(
            String userId, String vin, List<PlaceCandidate> candidates, String idempotencyKey) {
        synchronized (vehicleLock(vin)) {
            if (hasBlockingSessionForVin(vin)) {
                return new CommandResult("NOT_SENT", false, "Stop the active navigation session before sending a manual route. No route was sent.");
            }
            return tapVehicleClient.sendRoute(new TapAccessService.GrantIdentity(userId, "0".repeat(64)),
                    vin, candidates, idempotencyKey);
        }
    }

    public synchronized CommandResult sendManualRoute(TapAccessService.GrantIdentity identity, String vin,
                                                       List<PlaceCandidate> candidates, String idempotencyKey) {
        if (identity == null) throw new AccessDeniedException("Sign in through TAP first");
        synchronized (vehicleLock(vin)) {
            if (hasBlockingSessionForVin(vin)) {
                return new CommandResult("NOT_SENT", false, "Stop the active navigation session before sending a manual route. No route was sent.");
            }
            return tapVehicleClient.sendRoute(identity, vin, candidates, idempotencyKey);
        }
    }

    /**
     * Rebind persisted email-owned sessions to an operator-verified TAP subject.
     * Email IDs are stored lowercase; TAP subjects remain opaque and case-sensitive.
     */
    public void migrateOwner(String legacyEmail, String subject) {
        if (legacyEmail == null || legacyEmail.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A legacy email and TAP subject are required");
        }
        String legacyOwner = legacyEmail.trim().toLowerCase(Locale.ROOT);
        if (legacyOwner.equals(subject)) return;

        List<String> ownerIds = new ArrayList<>(List.of(legacyOwner, subject));
        ownerIds.sort(String::compareTo);
        withUserLocks(ownerIds, 0, () -> migrateOwnerLocked(legacyOwner, subject));
    }

    private void migrateOwnerLocked(String legacyOwner, String subject) {
        List<AutoNavSession> candidates = activeSessions.values().stream()
                .filter(session -> legacyOwner.equals(session.getUserId()))
                .sorted((left, right) -> left.getSessionId().compareTo(right.getSessionId()))
                .toList();
        if (candidates.isEmpty()) return;
        if (hasBlockingSessionForUser(subject)) {
            throw new IllegalStateException("The TAP account already owns a blocking auto-navigation session");
        }

        List<String> vehicleIds = candidates.stream()
                .map(AutoNavSession::getVin)
                .filter(vin -> vin != null)
                .map(vin -> vin.toUpperCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
        withVehicleLocks(vehicleIds, 0, () -> {
            List<AutoNavSession> sessions = candidates.stream()
                    .filter(session -> activeSessions.get(session.getSessionId()) == session)
                    .filter(session -> legacyOwner.equals(session.getUserId()))
                    .toList();
            if (sessions.isEmpty()) return;
            if (hasBlockingSessionForUser(subject)) {
                throw new IllegalStateException("The TAP account already owns a blocking auto-navigation session");
            }

            Map<AutoNavSession, Path> backups = new HashMap<>();
            try {
                for (AutoNavSession session : sessions) {
                    backups.put(session, backupSessionBeforeOwnerMigration(session));
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not back up sessions before owner migration", e);
            }

            List<AutoNavSession> changed = new ArrayList<>();
            try {
                for (AutoNavSession session : sessions) {
                    changed.add(session);
                    session.setUserId(subject);
                    persistSession(session);
                }
            } catch (RuntimeException e) {
                rollbackOwnerMigration(legacyOwner, changed, backups);
                throw new IllegalStateException("Could not persist TAP owner migration", e);
            }
        });
    }

    private void withUserLocks(List<String> ownerIds, int index, Runnable action) {
        if (index == ownerIds.size()) {
            action.run();
            return;
        }
        synchronized (userLock(ownerIds.get(index))) {
            withUserLocks(ownerIds, index + 1, action);
        }
    }

    private void withVehicleLocks(List<String> vehicleIds, int index, Runnable action) {
        if (index == vehicleIds.size()) {
            action.run();
            return;
        }
        synchronized (vehicleLock(vehicleIds.get(index))) {
            withVehicleLocks(vehicleIds, index + 1, action);
        }
    }

    private Path backupSessionBeforeOwnerMigration(AutoNavSession session) throws IOException {
        Path sessionFile = sessionsDirectory.resolve(session.getSessionId() + ".json");
        if (!Files.isRegularFile(sessionFile)) return null;

        Path backup = Files.createTempFile(sessionsDirectory,
                session.getSessionId() + ".json.owner-migration-", ".bak");
        try {
            Files.copy(sessionFile, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException e) {
            Files.deleteIfExists(backup);
            throw e;
        }
    }

    private void rollbackOwnerMigration(
            String legacyOwner,
            List<AutoNavSession> changed,
            Map<AutoNavSession, Path> backups) {
        for (int i = changed.size() - 1; i >= 0; i--) {
            AutoNavSession session = changed.get(i);
            session.setUserId(legacyOwner);
            Path original = sessionsDirectory.resolve(session.getSessionId() + ".json");
            Path backup = backups.get(session);
            try {
                if (backup == null) {
                    Files.deleteIfExists(original);
                } else {
                    restoreSessionBackup(backup, original);
                }
            } catch (IOException e) {
                log.error("Could not restore session {} after owner migration failed: {}",
                        session.getSessionId(), e.getMessage());
            }
        }
    }

    private void restoreSessionBackup(Path backup, Path original) throws IOException {
        Path temporary = Files.createTempFile(sessionsDirectory,
                original.getFileName().toString(), ".restore");
        try {
            Files.copy(backup, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, original, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, original, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Scheduled(fixedDelayString = "${navigation.poll-interval-ms:5000}")
    void pollRunningSessions() {
        for (AutoNavSession session : new ArrayList<>(activeSessions.values())) {
            if (session.getStatus() != AutoNavSession.Status.RUNNING) continue;
            String ownerId = session.getUserId();
            if (ownerId == null || ownerId.isBlank()) continue;
            synchronized (userLock(ownerId)) {
                if (!ownerId.equals(session.getUserId())) continue;
                synchronized (vehicleLock(session.getVin())) {
                    if (activeSessions.get(session.getSessionId()) != session
                            || session.getStatus() != AutoNavSession.Status.RUNNING) continue;
                    try {
                        pollSession(session);
                    } catch (RuntimeException e) {
                        log.error("Auto-navigation poll failed for session {}: {}", session.getSessionId(), e.getMessage());
                        if (session.getCommandState() == AutoNavSession.CommandState.SUBMITTING) {
                            session.setCommandState(AutoNavSession.CommandState.UNKNOWN);
                            session.setStatus(AutoNavSession.Status.ERROR);
                        } else if (session.getStatus() == AutoNavSession.Status.RUNNING) {
                            session.setStatus(AutoNavSession.Status.ERROR);
                        }
                        session.setLastError("Navigation state could not be persisted safely; review the vehicle before continuing.");
                        persistBestEffort(session);
                    }
                }
            }
        }
    }

    private void pollSession(AutoNavSession session) {
        session.setLastPollAt(LocalDateTime.now(clock));
        persistSession(session);

        TelemetryPage page;
        try {
        page = tapVehicleClient.telemetry(grantIdentity(session), session.getVin(), session.getTelemetryCursor());
        } catch (AccessDeniedException e) {
            failAuthorization(session, "TAP access expired, was revoked, or lacks entitlement; sign in again. Navigation stopped.");
            return;
        } catch (RuntimeException e) {
            session.setLastError("Vehicle telemetry is unavailable; no route was sent.");
            persistSession(session);
            return;
        }
        Instant localReceivedAt = clock.instant();
        if (page == null || !isClockSane(page.serverTime(), localReceivedAt)) {
            session.setLastError("TAP server time is missing or outside the local freshness window.");
            persistSession(session);
            return;
        }
        if (page.nextCursor() < 0 || (session.getTelemetryCursor() != null
                && page.nextCursor() < session.getTelemetryCursor())) {
            failSession(session, "TAP returned a telemetry cursor older than the saved cursor.");
            return;
        }
        if (page.gap()) {
            rebaseFromSnapshot(session, page, localReceivedAt);
            failSession(session, "Telemetry history has a gap; route progress needs manual review.");
            return;
        }

        if (session.getTelemetryCursor() == null) {
            if (!isFreshShift(page.snapshot(), page.serverTime(), localReceivedAt)) {
                session.setLastError("A fresh shift-state baseline is required before sending a route.");
                persistSession(session);
                return;
            }
            session.setTelemetryCursor(page.nextCursor());
            updateLatestTelemetry(session, page.snapshot(), page.serverTime(), localReceivedAt);
            persistSession(session);
            if (!page.hasMore() && isInitialSendReady(session, page.snapshot(), page.serverTime(), localReceivedAt)) {
                sendCurrentGroup(session);
            }
            return;
        }

        long replayCursor = session.getTelemetryCursor();
        long lastDeliveredCursor = replayCursor;
        List<Telemetry> events = page.events() == null ? List.of() : page.events();
        for (Telemetry event : events) {
            if (event.cursor() <= replayCursor) continue;
            if (event.cursor() <= lastDeliveredCursor) {
                failSession(session, "TAP telemetry replay events were not strictly ordered.");
                return;
            }
            lastDeliveredCursor = event.cursor();
            processEvent(session, event, page.serverTime(), localReceivedAt);
            if (session.getStatus() != AutoNavSession.Status.RUNNING) break;
        }
        if (session.getStatus() != AutoNavSession.Status.RUNNING) {
            persistSession(session);
            return;
        }
        if (page.nextCursor() < lastDeliveredCursor) {
            failSession(session, "TAP replay cursor precedes a delivered telemetry event.");
            return;
        }
        if (page.nextCursor() > lastDeliveredCursor) {
            session.setLastError("TAP replay has more telemetry to deliver; waiting before sending route.");
            persistSession(session);
            return;
        }
        session.setTelemetryCursor(Math.max(session.getTelemetryCursor(), page.nextCursor()));
        persistSession(session);

        if (page.hasMore() || session.getStatus() != AutoNavSession.Status.RUNNING
                || session.getCommandState() != AutoNavSession.CommandState.NONE
                || !session.isNextGroupReady()) return;

        TelemetryPage snapshotPage;
        try {
            snapshotPage = tapVehicleClient.telemetry(grantIdentity(session), session.getVin(), null);
        } catch (AccessDeniedException e) {
            failAuthorization(session, "TAP access expired, was revoked, or lacks entitlement; sign in again. Navigation stopped.");
            return;
        } catch (RuntimeException e) {
            session.setLastError("Current vehicle snapshot unavailable; no route sent.");
            persistSession(session);
            return;
        }
        Instant snapshotReceivedAt = clock.instant();
        if (snapshotPage == null || !isClockSane(snapshotPage.serverTime(), snapshotReceivedAt)) {
            session.setLastError("TAP current snapshot time is missing or outside local freshness window.");
            persistSession(session);
            return;
        }
        if (snapshotPage.gap()) {
            failSession(session, "Telemetry history gap while confirming route boundary.");
            return;
        }
        long savedCursor = session.getTelemetryCursor();
        if (snapshotPage.nextCursor() < savedCursor) {
            failSession(session, "TAP current snapshot cursor is older than saved replay cursor.");
            return;
        }
        List<Telemetry> snapshotEvents = snapshotPage.events() == null ? List.of() : snapshotPage.events();
        if (snapshotPage.hasMore() || !snapshotEvents.isEmpty() || snapshotPage.nextCursor() > savedCursor) {
            session.setLastError("New telemetry arrived while checking the vehicle snapshot; waiting for replay.");
            persistSession(session);
            return;
        }
        Telemetry snapshot = snapshotPage.snapshot();
        if (snapshot == null) {
            session.setLastError("TAP current vehicle snapshot is missing; no route sent.");
            persistSession(session);
            return;
        }
        updateLatestTelemetry(session, snapshot, snapshotPage.serverTime(), snapshotReceivedAt);
        persistSession(session);
        boolean initialBatch = session.getCurrentGroupIndex() == 0 && session.getCurrentWaypointIndex() == 0;
        if ((initialBatch && isInitialSendReady(session, snapshot, snapshotPage.serverTime(), snapshotReceivedAt))
        || (!initialBatch && isBoundarySendReady(session, snapshot, snapshotPage.serverTime(), snapshotReceivedAt))) {
            sendCurrentGroup(session);
        } else if (!initialBatch && isFreshShift(snapshot, snapshotPage.serverTime(), snapshotReceivedAt)
                && !isParked(snapshot.shiftState())) {
            failSession(session, "Vehicle left the confirmed waypoint before the next batch could be sent.");
        }
    }

    private void processEvent(AutoNavSession session, Telemetry event, Instant serverTime, Instant localReceivedAt) {
        if (event == null || event.cursor() <= session.getTelemetryCursor()) return;
        session.setTelemetryCursor(event.cursor());
        if (!isFreshEnvelope(event, serverTime, localReceivedAt)) {
            session.setPendingParkTransitionAt(null);
            return;
        }

        boolean newShift = isFreshTimestamp(event.shiftStateObservedAt(), serverTime, localReceivedAt)
                && (session.getLastShiftStateObservedAt() == null
                || event.shiftStateObservedAt().isAfter(session.getLastShiftStateObservedAt()));
        boolean freshLocation = isFreshLocation(event, serverTime, localReceivedAt);
        String previousShift = session.getLastShiftState();
        boolean parkTransition = newShift && isKnownNonParked(previousShift) && isParked(event.shiftState());

        if (newShift) {
            session.setLastShiftState(event.shiftState());
            session.setLastShiftStateObservedAt(event.shiftStateObservedAt());
            if (parkTransition && session.getCommandState() == AutoNavSession.CommandState.ACCEPTED
                    && session.getCommandSubmittedAt() != null
                    && event.shiftStateObservedAt().isAfter(session.getCommandSubmittedAt())) {
                session.setPendingParkTransitionAt(event.shiftStateObservedAt());
            } else if (!isParked(event.shiftState())) {
                session.setPendingParkTransitionAt(null);
            }
        }
        if (freshLocation && (session.getLastLocationObservedAt() == null
                || event.locationObservedAt().isAfter(session.getLastLocationObservedAt()))) {
            session.setLastLatitude(event.latitude());
            session.setLastLongitude(event.longitude());
            session.setLastLocationObservedAt(event.locationObservedAt());
        }

        Instant pendingPark = session.getPendingParkTransitionAt();
        if (pendingPark != null && !isFreshTimestamp(pendingPark, serverTime, localReceivedAt)) {
            session.setPendingParkTransitionAt(null);
            pendingPark = null;
        }
        // A cached pre-Park fix can describe somewhere the vehicle has already left.
        // Retain the transition for a later location packet, then consume it once.
        if (pendingPark != null && session.getCommandState() == AutoNavSession.CommandState.ACCEPTED
                && isParked(session.getLastShiftState())
                && session.getLastShiftStateObservedAt() != null
                && !session.getLastShiftStateObservedAt().isBefore(pendingPark)
                && freshLocation
                && isLocationAfterCommand(session, event.locationObservedAt())
                && isLocationWithinParkWindow(event.locationObservedAt(), pendingPark)
                && isNearCurrentWaypoint(event.latitude(), event.longitude(), session)) {
            boolean groupComplete = session.confirmCurrentWaypoint(pendingPark);
            log.info("Confirmed waypoint {} for auto-navigation session {}", session.getCompletedAddresses(), session.getSessionId());
            if (groupComplete && session.getStatus() != AutoNavSession.Status.COMPLETED) {
                log.info("Confirmed waypoint batch {} for session {}", session.getCurrentGroupIndex(), session.getSessionId());
            }
        } else if (session.getCurrentGroupIndex() > 0 && session.isNextGroupReady()
                && newShift && !isParked(event.shiftState())
                && session.getStatus() == AutoNavSession.Status.RUNNING) {
            failSession(session, "Vehicle left the confirmed waypoint before the next batch could be sent.");
        }
    }

    private boolean isInitialSendReady(
            AutoNavSession session, Telemetry snapshot, Instant serverTime, Instant localReceivedAt) {
        return session.getCurrentGroupIndex() == 0 && session.getCurrentWaypointIndex() == 0
                && isFreshShift(snapshot, serverTime, localReceivedAt)
                && isParked(snapshot.shiftState());
    }

    private boolean isBoundarySendReady(
            AutoNavSession session, Telemetry snapshot, Instant serverTime, Instant localReceivedAt) {
        int boundaryIndex = session.getCurrentGroupIndex() * 8 - 1;
        if (boundaryIndex < 0 || session.getLastArrivedWaypointIndex() != boundaryIndex
                || session.getLastParkTransitionAt() == null
                || !isFreshShift(snapshot, serverTime, localReceivedAt)
                || !isFreshLocation(snapshot, serverTime, localReceivedAt)
                || !isParked(snapshot.shiftState())
                || snapshot.shiftStateObservedAt().isBefore(session.getLastParkTransitionAt())
                || !isLocationAfterCommand(session, snapshot.locationObservedAt())
                || !isLocationWithinParkWindow(snapshot.locationObservedAt(), session.getLastParkTransitionAt())) {
            return false;
        }
        PlaceCandidate boundary = session.getAllAddresses().get(boundaryIndex);
        return distanceMeters(snapshot.latitude(), snapshot.longitude(), boundary.lat(), boundary.lon()) <= arrivalRadiusMeters;
    }

    private void sendCurrentGroup(AutoNavSession session) {
        List<PlaceCandidate> group;
        try {
            group = prepareCurrentGroup(session);
        } catch (RuntimeException e) {
            failSession(session, "Waypoint coordinates could not be validated; no route was sent.");
            return;
        }
        if (group.isEmpty()) {
            failSession(session, "The current waypoint batch is empty.");
            return;
        }

        String key = UUID.randomUUID().toString();
        session.setPendingCommandIdempotencyKey(key);
        session.setCommandSubmittedAt(clock.instant());
        session.setCommandState(AutoNavSession.CommandState.SUBMITTING);
        session.setNextGroupReady(false);
        session.setLastGroupSize(group.size());
        session.setLastError(null);
        try {
            persistSession(session);
        } catch (RuntimeException e) {
            session.setCommandState(AutoNavSession.CommandState.UNKNOWN);
            session.setStatus(AutoNavSession.Status.ERROR);
            session.setLastError("Could not persist the command key before sending; no route was sent.");
            persistBestEffort(session);
            return;
        }

        CommandResult result;
        try {
            result = tapVehicleClient.sendRoute(grantIdentity(session), session.getVin(), group, key);
        } catch (AccessDeniedException e) {
            session.setCommandState(AutoNavSession.CommandState.REJECTED);
            failAuthorization(session, "TAP denied the route command; sign in again. The command will not be retried automatically.");
            return;
        } catch (RuntimeException e) {
            session.setCommandState(AutoNavSession.CommandState.UNKNOWN);
            session.setStatus(AutoNavSession.Status.ERROR);
            session.setLastError("Route command outcome is unknown; it will not be retried.");
            persistBestEffort(session);
            log.error("TAP route outcome unknown for session {}: {}", session.getSessionId(), e.getMessage());
            return;
        }

        if (result != null && Boolean.TRUE.equals(result.accepted()) && "COMPLETED".equals(result.state())) {
            session.setCommandState(AutoNavSession.CommandState.ACCEPTED);
            session.setLastRouteSentAt(LocalDateTime.now(clock));
            session.setLastError(null);
        } else if (result != null && "REJECTED".equals(result.state())) {
            session.setCommandState(AutoNavSession.CommandState.REJECTED);
            session.setStatus(AutoNavSession.Status.ERROR);
            session.setLastError("TAP rejected the route command; no automatic retry will be made.");
        } else {
            session.setCommandState(AutoNavSession.CommandState.UNKNOWN);
            session.setStatus(AutoNavSession.Status.ERROR);
            session.setLastError("TAP did not explicitly confirm the route command; it will not be retried.");
        }
        persistBestEffort(session);
    }

    private List<PlaceCandidate> prepareCurrentGroup(AutoNavSession session) {
        List<PlaceCandidate> group = new ArrayList<>(session.getCurrentGroup());
        List<PlaceCandidate> toGeocode = group.stream()
                .filter(candidate -> !hasRouteCoordinates(candidate))
                .toList();
        Map<String, PlaceCandidate> geocodedByText = Map.of();
        if (!toGeocode.isEmpty()) {
            geocodedByText = geocodingClient.batchGeocode(session.getUserId(), toGeocode).stream()
                    .collect(Collectors.toMap(candidate -> candidate.text().trim(), candidate -> candidate, (first, ignored) -> first));
        }

        int startIndex = session.getCurrentGroupIndex() * 8;
        List<PlaceCandidate> prepared = new ArrayList<>(group.size());
        for (int i = 0; i < group.size(); i++) {
            PlaceCandidate candidate = group.get(i);
            if (!hasRouteCoordinates(candidate)) {
                candidate = geocodedByText.get(candidate.text().trim());
            }
            if (!hasRouteCoordinates(candidate)) {
                throw new IllegalStateException("A waypoint has no valid route coordinates");
            }
            session.replaceAddress(startIndex + i, candidate);
            prepared.add(candidate);
        }
        return prepared;
    }

    private boolean isFreshEnvelope(Telemetry telemetry, Instant serverTime, Instant localReceivedAt) {
        return telemetry.observedAt() != null && telemetry.receivedAt() != null
                && isFreshTimestamp(telemetry.observedAt(), serverTime, localReceivedAt)
                && isFreshTimestamp(telemetry.receivedAt(), serverTime, localReceivedAt);
    }

    private boolean isFreshShift(Telemetry telemetry, Instant serverTime, Instant localReceivedAt) {
        return telemetry != null && isFreshEnvelope(telemetry, serverTime, localReceivedAt)
                && telemetry.shiftState() != null && !telemetry.shiftState().isBlank()
                && isFreshTimestamp(telemetry.shiftStateObservedAt(), serverTime, localReceivedAt);
    }

    private boolean isFreshLocation(Telemetry telemetry, Instant serverTime, Instant localReceivedAt) {
        return telemetry != null && isFreshEnvelope(telemetry, serverTime, localReceivedAt)
                && validCoordinate(telemetry.latitude(), telemetry.longitude())
                && isFreshTimestamp(telemetry.locationObservedAt(), serverTime, localReceivedAt);
    }

    private boolean isLocationAfterCommand(AutoNavSession session, Instant locationObservedAt) {
        return session.getCommandSubmittedAt() != null && locationObservedAt != null
                && locationObservedAt.isAfter(session.getCommandSubmittedAt());
    }

    private boolean isLocationWithinParkWindow(Instant locationObservedAt, Instant parkObservedAt) {
        return locationObservedAt != null && parkObservedAt != null
                && !locationObservedAt.isBefore(parkObservedAt.minusSeconds(PARK_LOCATION_EARLY_TOLERANCE_SECONDS));
    }

    private boolean isFreshTimestamp(Instant timestamp, Instant serverTime, Instant localReceivedAt) {
        return timestamp != null && withinFreshnessWindow(timestamp, serverTime)
                && withinFreshnessWindow(timestamp, localReceivedAt);
    }

    private boolean isClockSane(Instant serverTime, Instant localReceivedAt) {
        return serverTime != null && withinFreshnessWindow(serverTime, localReceivedAt);
    }

    private boolean withinFreshnessWindow(Instant timestamp, Instant reference) {
        return timestamp != null && reference != null
                && Duration.between(timestamp, reference).abs().compareTo(Duration.ofSeconds(maxTelemetryAgeSeconds)) <= 0;
    }

    private void updateLatestTelemetry(
            AutoNavSession session, Telemetry telemetry, Instant serverTime, Instant localReceivedAt) {
        if (isFreshShift(telemetry, serverTime, localReceivedAt)
                && (session.getLastShiftStateObservedAt() == null
                || telemetry.shiftStateObservedAt().isAfter(session.getLastShiftStateObservedAt()))) {
            session.setLastShiftState(telemetry.shiftState());
            session.setLastShiftStateObservedAt(telemetry.shiftStateObservedAt());
        }
        if (isFreshLocation(telemetry, serverTime, localReceivedAt)
                && (session.getLastLocationObservedAt() == null
                || telemetry.locationObservedAt().isAfter(session.getLastLocationObservedAt()))) {
            session.setLastLatitude(telemetry.latitude());
            session.setLastLongitude(telemetry.longitude());
            session.setLastLocationObservedAt(telemetry.locationObservedAt());
        }
    }

    private void rebaseFromSnapshot(AutoNavSession session, TelemetryPage page, Instant localReceivedAt) {
        session.setTelemetryCursor(page.nextCursor());
        updateLatestTelemetry(session, page.snapshot(), page.serverTime(), localReceivedAt);
        persistSession(session);
    }

    private void failSession(AutoNavSession session, String reason) {
        session.setStatus(AutoNavSession.Status.ERROR);
        session.setLastError(reason);
        persistBestEffort(session);
    }

    private void failAuthorization(AutoNavSession session, String reason) {
        session.setPendingParkTransitionAt(null);
        failSession(session, reason);
    }

    private boolean isNearCurrentWaypoint(double latitude, double longitude, AutoNavSession session) {
        PlaceCandidate target = session.getCurrentWaypoint();
        return target != null && hasRouteCoordinates(target)
                && distanceMeters(latitude, longitude, target.lat(), target.lon()) <= arrivalRadiusMeters;
    }

    private static boolean hasRouteCoordinates(PlaceCandidate candidate) {
        return candidate != null && candidate.text() != null && !candidate.text().isBlank()
                && Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lon())
                && Math.abs(candidate.lat()) <= 90 && Math.abs(candidate.lon()) <= 180
                && !(candidate.lat() == 0 && candidate.lon() == 0)
                && candidate.pid() != null && candidate.pid().matches("[A-Za-z0-9_-]{1,512}");
    }

    private static boolean validCoordinate(Double latitude, Double longitude) {
        return latitude != null && longitude != null && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180
                && !(latitude == 0 && longitude == 0);
    }

    private static boolean isParked(String shiftState) {
        String state = normalizeShift(shiftState);
        return "P".equals(state) || "PARK".equals(state) || "PARKED".equals(state) || "SHIFTSTATEP".equals(state);
    }

    private static boolean isKnownNonParked(String shiftState) {
        String state = normalizeShift(shiftState);
        return "D".equals(state) || "DRIVE".equals(state) || "SHIFTSTATED".equals(state)
                || "R".equals(state) || "REVERSE".equals(state) || "SHIFTSTATER".equals(state)
                || "N".equals(state) || "NEUTRAL".equals(state) || "SHIFTSTATEN".equals(state);
    }

    private static String normalizeShift(String shiftState) {
        return shiftState == null ? "" : shiftState.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
    }

    private static double distanceMeters(double latitude1, double longitude1, double latitude2, double longitude2) {
        double earthRadiusMeters = 6_371_000;
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(longitude2 - longitude1);
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 2 * earthRadiusMeters * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private Object userLock(String userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new Object());
    }

    private Object vehicleLock(String vin) {
        return vehicleLocks.computeIfAbsent(vin.toUpperCase(Locale.ROOT), ignored -> new Object());
    }

    private boolean hasBlockingSessionForVin(String vin) {
        return activeSessions.values().stream()
                .anyMatch(session -> vin.equalsIgnoreCase(session.getVin())
                        && session.getStatus() != AutoNavSession.Status.STOPPED
                        && session.getStatus() != AutoNavSession.Status.COMPLETED);
    }

    private boolean hasOtherBlockingSessionForVin(String vin, String sessionId) {
        return activeSessions.values().stream()
                .anyMatch(session -> !sessionId.equals(session.getSessionId())
                        && vin.equalsIgnoreCase(session.getVin())
                        && isBlockingStatus(session.getStatus()));
    }

    private boolean hasBlockingSessionForUser(String userId) {
        return activeSessions.values().stream()
                .anyMatch(session -> userId.equals(session.getUserId())
                        && isBlockingStatus(session.getStatus()));
    }

    private boolean hasOtherBlockingSessionForUser(String userId, String sessionId) {
        return activeSessions.values().stream()
                .anyMatch(session -> !sessionId.equals(session.getSessionId())
                        && userId.equals(session.getUserId())
                        && isBlockingStatus(session.getStatus()));
    }

    private void initializeSessionsDirectory() {
        try {
            Files.createDirectories(sessionsDirectory);
        } catch (IOException e) {
            log.warn("Failed to initialize auto-navigation sessions directory: {}", e.getMessage());
        }
    }

    private void loadPersistedSessions() {
        if (!Files.isDirectory(sessionsDirectory)) return;
        try (var files = Files.list(sessionsDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(this::loadPersistedSession);
        } catch (IOException e) {
            log.warn("Failed to load persisted auto-navigation sessions: {}", e.getMessage());
        }
    }

    private void loadPersistedSession(Path file) {
        try {
            AutoNavSession session = objectMapper.readValue(file.toFile(), AutoNavSession.class);
            boolean changed = session.getPendingParkTransitionAt() != null;
            session.setPendingParkTransitionAt(null);
            if (session.getCommandState() == null && isBlockingStatus(session.getStatus())) {
                session.setStatus(AutoNavSession.Status.ERROR);
                session.setLastError("Saved session lacks telemetry progress state; stop it and create a new route.");
                changed = true;
            } else if (session.getStatus() == AutoNavSession.Status.RUNNING) {
                if (session.getCommandState() == AutoNavSession.CommandState.SUBMITTING) {
                    session.setCommandState(AutoNavSession.CommandState.UNKNOWN);
                    session.setStatus(AutoNavSession.Status.ERROR);
                    session.setLastError("Process restarted during a route command; inspect the vehicle before continuing.");
                } else if (session.getCommandState() == AutoNavSession.CommandState.UNKNOWN
                        || session.getCommandState() == AutoNavSession.CommandState.REJECTED) {
                    session.setStatus(AutoNavSession.Status.ERROR);
                    session.setLastError("Saved route command needs manual review; it will not be retried.");
                } else {
                    session.setStatus(AutoNavSession.Status.PAUSED);
                    session.setLastError("Paused after restart; inspect the vehicle, stop this session, and review remaining stops before creating a new route.");
                }
                changed = true;
            }
            activeSessions.put(session.getSessionId(), session);
            if (changed) persistSession(session);
        } catch (Exception e) {
            log.warn("Failed to load auto-navigation session file {}: {}", file.getFileName(), e.getMessage());
        }
    }

    private boolean isBlockingStatus(AutoNavSession.Status status) {
        return status == AutoNavSession.Status.CREATED || status == AutoNavSession.Status.RUNNING
                || status == AutoNavSession.Status.PAUSED || status == AutoNavSession.Status.ERROR;
    }

    private void persistSession(AutoNavSession session) {
        Path temporary = null;
        try {
            Files.createDirectories(sessionsDirectory);
            Path sessionFile = sessionsDirectory.resolve(session.getSessionId() + ".json");
            temporary = Files.createTempFile(sessionsDirectory, session.getSessionId(), ".tmp");
            ObjectNode persisted = objectMapper.valueToTree(session);
            if (session.getGrantId() == null) persisted.putNull("grantId");
            else persisted.put("grantId", session.getGrantId());
            objectMapper.writeValue(temporary.toFile(), persisted);
            try {
                Files.move(temporary, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, sessionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Failed to persist auto-navigation session " + session.getSessionId(), e);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    private void persistBestEffort(AutoNavSession session) {
        try {
            persistSession(session);
        } catch (RuntimeException e) {
            log.error("Failed to persist auto-navigation session {}: {}", session.getSessionId(), e.getMessage());
        }
    }

    private AutoNavSession loadSessionFromDisk(String sessionId) {
        if (sessionId == null || sessionId.contains("/") || sessionId.contains("\\")) return null;
        try {
            Path sessionFile = sessionsDirectory.resolve(sessionId + ".json");
            if (Files.exists(sessionFile)) return objectMapper.readValue(sessionFile.toFile(), AutoNavSession.class);
        } catch (JacksonException e) {
            log.warn("Failed to load auto-navigation session {}: {}", sessionId, e.getMessage());
        }
        return null;
    }

    public void cleanupOldSessions() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(MAX_SESSION_AGE_HOURS);
        activeSessions.entrySet().removeIf(entry -> {
            AutoNavSession session = entry.getValue();
            if (session.getCreatedAt() != null && session.getCreatedAt().isBefore(cutoff)
                && (session.getStatus() == AutoNavSession.Status.STOPPED
                || session.getStatus() == AutoNavSession.Status.COMPLETED)
                && session.getCommandState() != AutoNavSession.CommandState.SUBMITTING
                && session.getCommandState() != AutoNavSession.CommandState.UNKNOWN) {
                synchronized (vehicleLock(session.getVin())) {
                    try {
                        Files.deleteIfExists(sessionsDirectory.resolve(session.getSessionId() + ".json"));
                    } catch (IOException e) {
                        log.warn("Failed to delete old auto-navigation session {}: {}", session.getSessionId(), e.getMessage());
                    }
                }
                return true;
            }
            return false;
        });
    }
}
