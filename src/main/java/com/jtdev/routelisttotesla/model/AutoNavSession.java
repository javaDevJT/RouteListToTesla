package com.jtdev.routelisttotesla.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents an automatic navigation session that queues and sends address groups
 * to a Tesla vehicle as it completes each destination.
 */
public class AutoNavSession {
    
    public enum Status {
        CREATED,        // Session created, not started
        RUNNING,        // Actively monitoring and sending routes
        PAUSED,         // Temporarily paused by user
        COMPLETED,      // All addresses sent and navigation complete
        STOPPED,        // Manually stopped by user
        ERROR           // Error occurred
    }
    
    private String sessionId;
    private String vin;
    private String userId;
    private List<PlaceCandidate> allAddresses;
    private int currentGroupIndex;
    private int totalGroups;
    private Status status;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastPollAt;
    private int completedAddresses;
    
    public AutoNavSession() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.status = Status.CREATED;
        this.currentGroupIndex = 0;
        this.completedAddresses = 0;
    }
    
    public AutoNavSession(String sessionId, String vin, String userId, List<PlaceCandidate> allAddresses) {
        this();
        this.sessionId = sessionId;
        this.vin = vin;
        this.userId = userId;
        this.allAddresses = allAddresses;
        this.totalGroups = (int) Math.ceil(allAddresses.size() / 8.0);
    }
    
    // Getters and Setters
    public String getSessionId() {
        return sessionId;
    }
    
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }
    
    public String getVin() {
        return vin;
    }
    
    public void setVin(String vin) {
        this.vin = vin;
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public List<PlaceCandidate> getAllAddresses() {
        return allAddresses;
    }
    
    public void setAllAddresses(List<PlaceCandidate> allAddresses) {
        this.allAddresses = allAddresses;
        this.totalGroups = (int) Math.ceil(allAddresses.size() / 8.0);
    }
    
    public int getCurrentGroupIndex() {
        return currentGroupIndex;
    }
    
    public void setCurrentGroupIndex(int currentGroupIndex) {
        this.currentGroupIndex = currentGroupIndex;
        this.updatedAt = LocalDateTime.now();
    }
    
    public int getTotalGroups() {
        return totalGroups;
    }
    
    public void setTotalGroups(int totalGroups) {
        this.totalGroups = totalGroups;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
        this.updatedAt = LocalDateTime.now();
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
    
    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
    
    public LocalDateTime getLastPollAt() {
        return lastPollAt;
    }
    
    public void setLastPollAt(LocalDateTime lastPollAt) {
        this.lastPollAt = lastPollAt;
    }
    
    public int getCompletedAddresses() {
        return completedAddresses;
    }
    
    public void setCompletedAddresses(int completedAddresses) {
        this.completedAddresses = completedAddresses;
    }
    
    /**
     * Get the current group of addresses (up to 8)
     */
    public List<PlaceCandidate> getCurrentGroup() {
        int startIndex = currentGroupIndex * 8;
        int endIndex = Math.min(startIndex + 8, allAddresses.size());
        if (startIndex >= allAddresses.size()) {
            return List.of();
        }
        return allAddresses.subList(startIndex, endIndex);
    }
    
    /**
     * Move to the next group and update completed count
     */
    public void advanceToNextGroup() {
        int groupSize = getCurrentGroup().size();
        this.completedAddresses += groupSize;
        this.currentGroupIndex++;
        this.updatedAt = LocalDateTime.now();
        
        if (this.currentGroupIndex >= this.totalGroups) {
            this.status = Status.COMPLETED;
        }
    }
    
    /**
     * Check if there are more groups to process
     */
    public boolean hasMoreGroups() {
        return currentGroupIndex < totalGroups;
    }
    
    /**
     * Get progress percentage
     */
    public int getProgressPercentage() {
        if (allAddresses == null || allAddresses.isEmpty()) {
            return 0;
        }
        return (int) ((completedAddresses * 100.0) / allAddresses.size());
    }
}
