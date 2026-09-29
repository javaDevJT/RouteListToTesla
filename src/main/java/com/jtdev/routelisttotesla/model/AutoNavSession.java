package com.jtdev.routelisttotesla.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** A persisted, ordered route queue for one vehicle. */
public class AutoNavSession {

    public enum Status {
        CREATED,
        RUNNING,
        PAUSED,
        COMPLETED,
        STOPPED,
        ERROR
    }

    public enum CommandState {
        NONE,
        SUBMITTING,
        ACCEPTED,
        UNKNOWN,
        REJECTED
    }

    private String sessionId;
    private String vin;
    private String userId;
    private String grantId;
    private List<PlaceCandidate> allAddresses;
    private int currentGroupIndex;
    private int currentWaypointIndex;
    private int totalGroups;
    private Status status;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastPollAt;
    private LocalDateTime lastRouteSentAt;
    private int lastGroupSize;
    private int completedAddresses;
    private Long telemetryCursor;
    private String lastShiftState;
    private Instant lastShiftStateObservedAt;
    private Double lastLatitude;
    private Double lastLongitude;
    private Instant lastLocationObservedAt;
    private Instant lastParkTransitionAt;
    private Instant pendingParkTransitionAt;
    private int lastArrivedWaypointIndex = -1;
    private String pendingCommandIdempotencyKey;
    private Instant commandSubmittedAt;
    private CommandState commandState;
    private boolean nextGroupReady;

    public AutoNavSession() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = Status.CREATED;
        this.currentGroupIndex = 0;
        this.currentWaypointIndex = 0;
        this.completedAddresses = 0;
    }

    public AutoNavSession(String sessionId, String vin, String userId, String grantId,
                          List<PlaceCandidate> allAddresses) {
        this();
        this.sessionId = sessionId;
        this.vin = vin;
        this.userId = userId;
        this.grantId = grantId;
        setAllAddresses(allAddresses);
        this.commandState = CommandState.NONE;
        this.nextGroupReady = true;
    }

    /** Compatibility constructor for pre-grant persisted sessions; these sessions fail closed on resume. */
    public AutoNavSession(String sessionId, String vin, String userId, List<PlaceCandidate> allAddresses) {
        this(sessionId, vin, userId, null, allAddresses);
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getVin() { return vin; }
    public void setVin(String vin) { this.vin = vin; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    /** Write-only for public JSON; AutoNavigationService persists this internal binding explicitly. */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getGrantId() { return grantId; }
    public void setGrantId(String grantId) { this.grantId = grantId; }
    public List<PlaceCandidate> getAllAddresses() { return allAddresses; }

    public void setAllAddresses(List<PlaceCandidate> allAddresses) {
        this.allAddresses = allAddresses == null ? null : new ArrayList<>(allAddresses);
        this.totalGroups = this.allAddresses == null ? 0 : (this.allAddresses.size() + 7) / 8;
    }

    public int getCurrentGroupIndex() { return currentGroupIndex; }
    public void setCurrentGroupIndex(int currentGroupIndex) { this.currentGroupIndex = currentGroupIndex; }
    public int getCurrentWaypointIndex() { return currentWaypointIndex; }
    public void setCurrentWaypointIndex(int currentWaypointIndex) { this.currentWaypointIndex = currentWaypointIndex; }
    public int getTotalGroups() { return totalGroups; }
    public void setTotalGroups(int totalGroups) { this.totalGroups = totalGroups; }
    public Status getStatus() { return status; }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public String getLastError() { return lastError; }

    public void setLastError(String lastError) {
        this.lastError = lastError;
        this.updatedAt = LocalDateTime.now();
    }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public LocalDateTime getLastPollAt() { return lastPollAt; }
    public void setLastPollAt(LocalDateTime lastPollAt) { this.lastPollAt = lastPollAt; }
    public LocalDateTime getLastRouteSentAt() { return lastRouteSentAt; }
    public void setLastRouteSentAt(LocalDateTime lastRouteSentAt) { this.lastRouteSentAt = lastRouteSentAt; }
    public int getLastGroupSize() { return lastGroupSize; }
    public void setLastGroupSize(int lastGroupSize) { this.lastGroupSize = lastGroupSize; }
    public int getCompletedAddresses() { return completedAddresses; }
    public void setCompletedAddresses(int completedAddresses) { this.completedAddresses = completedAddresses; }
    public Long getTelemetryCursor() { return telemetryCursor; }
    public void setTelemetryCursor(Long telemetryCursor) { this.telemetryCursor = telemetryCursor; }
    public String getLastShiftState() { return lastShiftState; }
    public void setLastShiftState(String lastShiftState) { this.lastShiftState = lastShiftState; }
    public Instant getLastShiftStateObservedAt() { return lastShiftStateObservedAt; }
    public void setLastShiftStateObservedAt(Instant lastShiftStateObservedAt) { this.lastShiftStateObservedAt = lastShiftStateObservedAt; }
    public Double getLastLatitude() { return lastLatitude; }
    public void setLastLatitude(Double lastLatitude) { this.lastLatitude = lastLatitude; }
    public Double getLastLongitude() { return lastLongitude; }
    public void setLastLongitude(Double lastLongitude) { this.lastLongitude = lastLongitude; }
    public Instant getLastLocationObservedAt() { return lastLocationObservedAt; }
    public void setLastLocationObservedAt(Instant lastLocationObservedAt) { this.lastLocationObservedAt = lastLocationObservedAt; }
    public Instant getLastParkTransitionAt() { return lastParkTransitionAt; }
    public Instant getPendingParkTransitionAt() { return pendingParkTransitionAt; }
    public void setPendingParkTransitionAt(Instant pendingParkTransitionAt) { this.pendingParkTransitionAt = pendingParkTransitionAt; }
    public void setLastParkTransitionAt(Instant lastParkTransitionAt) { this.lastParkTransitionAt = lastParkTransitionAt; }
    public int getLastArrivedWaypointIndex() { return lastArrivedWaypointIndex; }
    public void setLastArrivedWaypointIndex(int lastArrivedWaypointIndex) { this.lastArrivedWaypointIndex = lastArrivedWaypointIndex; }
    public String getPendingCommandIdempotencyKey() { return pendingCommandIdempotencyKey; }
    public Instant getCommandSubmittedAt() { return commandSubmittedAt; }
    public void setCommandSubmittedAt(Instant commandSubmittedAt) { this.commandSubmittedAt = commandSubmittedAt; }
    public void setPendingCommandIdempotencyKey(String pendingCommandIdempotencyKey) { this.pendingCommandIdempotencyKey = pendingCommandIdempotencyKey; }
    public CommandState getCommandState() { return commandState; }
    public void setCommandState(CommandState commandState) { this.commandState = commandState; }
    public boolean isNextGroupReady() { return nextGroupReady; }
    public void setNextGroupReady(boolean nextGroupReady) { this.nextGroupReady = nextGroupReady; }

    /** The current ordered batch, capped at the Tesla eight-waypoint limit. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public List<PlaceCandidate> getCurrentGroup() {
        if (allAddresses == null) return List.of();
        int startIndex = currentGroupIndex * 8;
        if (startIndex >= allAddresses.size()) return List.of();
        return allAddresses.subList(startIndex, Math.min(startIndex + 8, allAddresses.size()));
    }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public PlaceCandidate getCurrentWaypoint() {
        return allAddresses == null || currentWaypointIndex >= allAddresses.size()
                ? null : allAddresses.get(currentWaypointIndex);
    }

    public void replaceAddress(int index, PlaceCandidate address) {
        allAddresses.set(index, address);
        updatedAt = LocalDateTime.now();
    }

    /** Advances exactly one waypoint after a fresh park transition is matched. */
    public boolean confirmCurrentWaypoint(Instant parkedAt) {
        if (getCurrentWaypoint() == null || commandState != CommandState.ACCEPTED) return false;
        int completedIndex = currentWaypointIndex;
        currentWaypointIndex++;
        completedAddresses = currentWaypointIndex;
        lastArrivedWaypointIndex = completedIndex;
        lastParkTransitionAt = parkedAt;
        pendingParkTransitionAt = null;

        int endOfGroup = Math.min((currentGroupIndex + 1) * 8, allAddresses.size());
        boolean groupComplete = currentWaypointIndex >= endOfGroup;
        if (groupComplete) {
            currentGroupIndex++;
            pendingCommandIdempotencyKey = null;
            commandState = CommandState.NONE;
            nextGroupReady = currentWaypointIndex < allAddresses.size();
            if (!nextGroupReady) status = Status.COMPLETED;
        }
        updatedAt = LocalDateTime.now();
        return groupComplete;
    }

    public boolean hasMoreGroups() { return currentGroupIndex < totalGroups; }

    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    public int getProgressPercentage() {
        if (allAddresses == null || allAddresses.isEmpty()) return 0;
        return (int) ((completedAddresses * 100.0) / allAddresses.size());
    }
}
