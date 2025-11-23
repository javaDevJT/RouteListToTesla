package com.jtdev.routelisttotesla.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.FleetApi;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for managing automatic navigation sessions.
 * Monitors vehicle navigation state and automatically sends the next group of addresses
 * when the vehicle has no active destination.
 */
@Service
public class AutoNavigationService {
    
    private static final Logger log = LoggerFactory.getLogger(AutoNavigationService.class);
    private static final String SESSIONS_DIR = "cache/auto-nav-sessions";
    private static final int POLL_INTERVAL_SECONDS = 30;
    private static final int MAX_SESSION_AGE_HOURS = 24;
    
    // Estimated time per stop in minutes - used to calculate when to check for next route
    private static final int MINUTES_PER_STOP = 2;
    
    private final FleetApi fleetApi;
    private final GeocodingClient geocodingClient;
    private final ObjectMapper objectMapper;
    
    {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    
    // In-memory cache of active sessions for quick access
    private final ConcurrentHashMap<String, AutoNavSession> activeSessions = new ConcurrentHashMap<>();
    // Track which sessions are currently being monitored
    private final ConcurrentHashMap<String, Boolean> monitoringThreads = new ConcurrentHashMap<>();
    
    @Autowired
    public AutoNavigationService(FleetApi fleetApi, GeocodingClient geocodingClient) {
        this.fleetApi = fleetApi;
        this.geocodingClient = geocodingClient;
        initializeSessionsDirectory();
        loadPersistedSessions();
    }
    
    private void initializeSessionsDirectory() {
        try {
            Files.createDirectories(Paths.get(SESSIONS_DIR));
        } catch (IOException e) {
            log.warn("Failed to create auto-nav sessions directory: {}", e.getMessage());
        }
    }
    
    /**
     * Load any persisted sessions from disk on startup
     */
    private void loadPersistedSessions() {
        try {
            File sessionsDir = new File(SESSIONS_DIR);
            File[] files = sessionsDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    try {
                        AutoNavSession session = objectMapper.readValue(file, AutoNavSession.class);
                        // Only load sessions that are still running and not too old
                        if (session.getStatus() == AutoNavSession.Status.RUNNING && 
                            session.getCreatedAt().plusHours(MAX_SESSION_AGE_HOURS).isAfter(LocalDateTime.now())) {
                            activeSessions.put(session.getSessionId(), session);
                            log.info("Loaded persisted auto-nav session: {}", session.getSessionId());
                            // Resume monitoring for running sessions
                            startMonitoringAsync(session.getSessionId());
                        }
                    } catch (IOException e) {
                        log.warn("Failed to load session from {}: {}", file.getName(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted sessions: {}", e.getMessage());
        }
    }
    
    /**
     * Create a new auto-navigation session
     */
    public AutoNavSession createSession(String vin, String userId, List<PlaceCandidate> addresses) {
        String sessionId = UUID.randomUUID().toString();
        AutoNavSession session = new AutoNavSession(sessionId, vin, userId, addresses);
        
        activeSessions.put(sessionId, session);
        persistSession(session);
        
        log.info("Created auto-nav session {} for VIN {} with {} addresses ({} groups)", 
                sessionId, vin, addresses.size(), session.getTotalGroups());
        
        return session;
    }
    
    /**
     * Start automatic navigation for a session
     */
    public AutoNavSession startSession(String sessionId) {
        AutoNavSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        
        if (session.getStatus() == AutoNavSession.Status.RUNNING) {
            log.info("Session {} is already running", sessionId);
            return session;
        }
        
        session.setStatus(AutoNavSession.Status.RUNNING);
        persistSession(session);
        
        // Start monitoring in background
        startMonitoringAsync(sessionId);
        
        log.info("Started auto-nav session {}", sessionId);
        return session;
    }
    
    /**
     * Stop an active session
     */
    public AutoNavSession stopSession(String sessionId) {
        AutoNavSession session = getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        
        session.setStatus(AutoNavSession.Status.STOPPED);
        monitoringThreads.put(sessionId, false);
        persistSession(session);
        
        log.info("Stopped auto-nav session {}", sessionId);
        return session;
    }
    
    /**
     * Get session by ID
     */
    public AutoNavSession getSession(String sessionId) {
        AutoNavSession session = activeSessions.get(sessionId);
        if (session == null) {
            // Try loading from disk
            session = loadSessionFromDisk(sessionId);
            if (session != null) {
                activeSessions.put(sessionId, session);
            }
        }
        return session;
    }
    
    /**
     * Get active session for a user (if any)
     */
    public AutoNavSession getActiveSessionForUser(String userId) {
        return activeSessions.values().stream()
                .filter(s -> userId.equals(s.getUserId()))
                .filter(s -> s.getStatus() == AutoNavSession.Status.RUNNING || 
                            s.getStatus() == AutoNavSession.Status.CREATED)
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Start the monitoring loop asynchronously
     */
    @Async
    public void startMonitoringAsync(String sessionId) {
        if (monitoringThreads.getOrDefault(sessionId, false)) {
            log.info("Monitoring already active for session {}", sessionId);
            return;
        }
        
        monitoringThreads.put(sessionId, true);
        
        try {
            monitorAndNavigate(sessionId);
        } catch (Exception e) {
            log.error("Error in monitoring loop for session {}: {}", sessionId, e.getMessage());
            AutoNavSession session = getSession(sessionId);
            if (session != null) {
                session.setStatus(AutoNavSession.Status.ERROR);
                session.setLastError(e.getMessage());
                persistSession(session);
            }
        } finally {
            monitoringThreads.put(sessionId, false);
        }
    }
    
    /**
     * Main monitoring loop - polls vehicle and sends routes when ready
     */
    private void monitorAndNavigate(String sessionId) throws InterruptedException {
        log.info("Starting monitoring loop for session {}", sessionId);
        
        while (monitoringThreads.getOrDefault(sessionId, false)) {
            AutoNavSession session = getSession(sessionId);
            
            if (session == null) {
                log.warn("Session {} not found, stopping monitoring", sessionId);
                break;
            }
            
            if (session.getStatus() != AutoNavSession.Status.RUNNING) {
                log.info("Session {} is no longer running (status: {}), stopping monitoring", 
                        sessionId, session.getStatus());
                break;
            }
            
            if (!session.hasMoreGroups()) {
                log.info("Session {} completed all groups", sessionId);
                session.setStatus(AutoNavSession.Status.COMPLETED);
                persistSession(session);
                break;
            }
            
            try {
                session.setLastPollAt(LocalDateTime.now());
                
                // Calculate if enough time has passed since last route was sent
                // Assume ~2 minutes per stop for deliveries/pickups
                boolean minTimeElapsed = hasMinimumTimeElapsed(session);
                
                if (!minTimeElapsed) {
                    long minutesRemaining = getMinutesUntilNextCheck(session);
                    log.debug("Waiting for minimum time to elapse. {} minutes remaining before next check for VIN {}", 
                            minutesRemaining, session.getVin());
                    persistSession(session);  // Persist to update lastPollAt for UI
                } else if (isVehicleReadyForNextRoute(session.getVin())) {
                    // Vehicle has no active destination and minimum time has passed
                    log.info("Vehicle {} ready for next route, sending group {}", 
                            session.getVin(), session.getCurrentGroupIndex() + 1);
                    
                    boolean sent = sendCurrentGroup(session);
                    if (sent) {
                        int groupSize = session.getCurrentGroup().size();
                        session.setLastRouteSentAt(LocalDateTime.now());
                        session.setLastGroupSize(groupSize);
                        session.advanceToNextGroup();
                        log.info("Sent group {} of {}, {} addresses completed", 
                                session.getCurrentGroupIndex(), session.getTotalGroups(),
                                session.getCompletedAddresses());
                    } else {
                        log.warn("Failed to send group {} for session {}", 
                                session.getCurrentGroupIndex() + 1, sessionId);
                    }
                    persistSession(session);
                } else {
                    log.debug("Vehicle {} still navigating, waiting...", session.getVin());
                    persistSession(session);  // Persist to update lastPollAt for UI
                }
                
            } catch (Exception e) {
                log.error("Error polling/sending for session {}: {}", sessionId, e.getMessage());
                session.setLastError(e.getMessage());
                persistSession(session);
            }
            
            // Wait before next poll
            Thread.sleep(POLL_INTERVAL_SECONDS * 1000L);
        }
        
        log.info("Monitoring loop ended for session {}", sessionId);
    }
    
    /**
     * Check if enough time has passed since the last route was sent.
     * We estimate ~2 minutes per stop to allow for deliveries/pickups.
     */
    private boolean hasMinimumTimeElapsed(AutoNavSession session) {
        LocalDateTime lastSent = session.getLastRouteSentAt();
        
        // If no route has been sent yet (first group), proceed immediately
        if (lastSent == null) {
            return true;
        }
        
        int lastGroupSize = session.getLastGroupSize();
        if (lastGroupSize == 0) {
            lastGroupSize = 1; // Minimum 1 stop
        }
        
        // Calculate minimum wait time: lastGroupSize * MINUTES_PER_STOP
        int minimumMinutes = lastGroupSize * MINUTES_PER_STOP;
        LocalDateTime earliestNextCheck = lastSent.plusMinutes(minimumMinutes);
        
        return LocalDateTime.now().isAfter(earliestNextCheck);
    }
    
    /**
     * Get the number of minutes remaining until we should check for the next route
     */
    private long getMinutesUntilNextCheck(AutoNavSession session) {
        LocalDateTime lastSent = session.getLastRouteSentAt();
        if (lastSent == null) {
            return 0;
        }
        
        int lastGroupSize = Math.max(1, session.getLastGroupSize());
        int minimumMinutes = lastGroupSize * MINUTES_PER_STOP;
        LocalDateTime earliestNextCheck = lastSent.plusMinutes(minimumMinutes);
        
        long seconds = java.time.Duration.between(LocalDateTime.now(), earliestNextCheck).getSeconds();
        return Math.max(0, (seconds + 59) / 60); // Round up to nearest minute
    }
    
    /**
     * Check if the vehicle has no active navigation destination
     */
    private boolean isVehicleReadyForNextRoute(String vin) {
        try {
            // Use use_cache=false to ensure we get fresh data from the vehicle
            String vehicleDataJson = fleetApi.vehicleData(vin, Map.of(
                    "endpoints", "drive_state",
                    "use_cache", "false"
            ));
            JsonNode root = objectMapper.readTree(vehicleDataJson);
            
            // Navigate to drive_state in the response
            JsonNode driveState = root.path("response").path("drive_state");
            if (driveState.isMissingNode()) {
                driveState = root.path("drive_state");
            }
            
            // Check active_route_destination - if null/empty, vehicle is ready
            JsonNode activeRoute = driveState.path("active_route_destination");
            
            if (activeRoute.isMissingNode() || activeRoute.isNull() || 
                (activeRoute.isTextual() && activeRoute.asText().isEmpty())) {
                log.debug("No active route destination for VIN {}", vin);
                return true;
            }
            
            log.debug("Active route destination for VIN {}: {}", vin, activeRoute.asText());
            return false;
            
        } catch (Exception e) {
            log.warn("Error checking vehicle state for VIN {}: {}", vin, e.getMessage());
            // On error, assume not ready to avoid sending duplicate routes
            return false;
        }
    }
    
    /**
     * Send the current group of addresses to the vehicle
     */
    private boolean sendCurrentGroup(AutoNavSession session) {
        List<PlaceCandidate> group = session.getCurrentGroup();
        if (group.isEmpty()) {
            return false;
        }
        
        // Filter out empty addresses and ensure geocoding
        List<PlaceCandidate> validAddresses = group.stream()
                .filter(c -> c.text() != null && !c.text().trim().isEmpty())
                .collect(Collectors.toList());
        
        // Re-geocode any that need it
        List<PlaceCandidate> toGeocode = validAddresses.stream()
                .filter(c -> c.lat() == 0 || c.lon() == 0 || c.pid() == null)
                .collect(Collectors.toList());
        
        if (!toGeocode.isEmpty()) {
            try {
                List<PlaceCandidate> geocoded = geocodingClient.batchGeocode(toGeocode);
                Map<String, PlaceCandidate> geocodedMap = geocoded.stream()
                        .collect(Collectors.toMap(c -> c.text().trim(), c -> c, (a, b) -> a));
                
                validAddresses = validAddresses.stream()
                        .map(c -> {
                            if (c.lat() == 0 || c.lon() == 0 || c.pid() == null) {
                                return geocodedMap.getOrDefault(c.text().trim(), c);
                            }
                            return c;
                        })
                        .filter(c -> c.lat() != 0 && c.lon() != 0 && c.pid() != null)
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Error geocoding addresses: {}", e.getMessage());
                // Continue with addresses that already have coordinates
                validAddresses = validAddresses.stream()
                        .filter(c -> c.lat() != 0 && c.lon() != 0 && c.pid() != null)
                        .collect(Collectors.toList());
            }
        }
        
        if (validAddresses.isEmpty()) {
            log.warn("No valid addresses in current group for session {}", session.getSessionId());
            return true; // Consider it done, move to next group
        }
        
        // Send waypoints to Tesla
        String waypointsParam = validAddresses.stream()
                .map(x -> "refId:" + x.pid())
                .collect(Collectors.joining(","));
        
        String result = fleetApi.commandNavigationWaypointsRequest(
                session.getVin(), 
                Map.of("waypoints", waypointsParam)
        );
        
        boolean success = result.contains("true");
        log.info("Sent {} waypoints to VIN {}: {}", validAddresses.size(), session.getVin(), 
                success ? "SUCCESS" : "FAILED");
        
        return success;
    }
    
    /**
     * Persist session to disk
     */
    private void persistSession(AutoNavSession session) {
        try {
            Path sessionFile = Paths.get(SESSIONS_DIR, session.getSessionId() + ".json");
            objectMapper.writeValue(sessionFile.toFile(), session);
        } catch (IOException e) {
            log.warn("Failed to persist session {}: {}", session.getSessionId(), e.getMessage());
        }
    }
    
    /**
     * Load session from disk
     */
    private AutoNavSession loadSessionFromDisk(String sessionId) {
        try {
            Path sessionFile = Paths.get(SESSIONS_DIR, sessionId + ".json");
            if (Files.exists(sessionFile)) {
                return objectMapper.readValue(sessionFile.toFile(), AutoNavSession.class);
            }
        } catch (IOException e) {
            log.warn("Failed to load session {} from disk: {}", sessionId, e.getMessage());
        }
        return null;
    }
    
    /**
     * Delete old sessions (cleanup)
     */
    public void cleanupOldSessions() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(MAX_SESSION_AGE_HOURS);
        
        activeSessions.entrySet().removeIf(entry -> {
            AutoNavSession session = entry.getValue();
            if (session.getCreatedAt().isBefore(cutoff)) {
                // Delete from disk too
                try {
                    Path sessionFile = Paths.get(SESSIONS_DIR, session.getSessionId() + ".json");
                    Files.deleteIfExists(sessionFile);
                } catch (IOException e) {
                    log.warn("Failed to delete old session file: {}", e.getMessage());
                }
                return true;
            }
            return false;
        });
    }
}
