package com.jtdev.routelisttotesla.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Represents a user session that stores processed addresses for session recovery.
 * Allows users to reload their previous work within 8 hours without re-uploading images.
 */
public class UserSession {
    
    private String userId;
    private String vin;
    private String defaultState;
    private List<PlaceCandidate> addresses;
    private List<String> imageHashes;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private String activeAutoNavSessionId;
    
    private static final int EXPIRATION_HOURS = 8;
    
    public UserSession() {
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plusHours(EXPIRATION_HOURS);
    }
    
    public UserSession(String userId, String vin, String defaultState, 
                       List<PlaceCandidate> addresses, List<String> imageHashes) {
        this();
        this.userId = userId;
        this.vin = vin;
        this.defaultState = defaultState;
        this.addresses = addresses;
        this.imageHashes = imageHashes;
    }
    
    // Getters and Setters
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getVin() {
        return vin;
    }
    
    public void setVin(String vin) {
        this.vin = vin;
    }
    
    public String getDefaultState() {
        return defaultState;
    }
    
    public void setDefaultState(String defaultState) {
        this.defaultState = defaultState;
    }
    
    public List<PlaceCandidate> getAddresses() {
        return addresses;
    }
    
    public void setAddresses(List<PlaceCandidate> addresses) {
        this.addresses = addresses;
    }
    
    public List<String> getImageHashes() {
        return imageHashes;
    }
    
    public void setImageHashes(List<String> imageHashes) {
        this.imageHashes = imageHashes;
    }
    
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
    
    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
    
    public void setExpiresAt(LocalDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
    
    public String getActiveAutoNavSessionId() {
        return activeAutoNavSessionId;
    }
    
    public void setActiveAutoNavSessionId(String activeAutoNavSessionId) {
        this.activeAutoNavSessionId = activeAutoNavSessionId;
    }
    
    /**
     * Check if the session has expired
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
    
    /**
     * Check if the session is still valid (not expired)
     */
    public boolean isValid() {
        return !isExpired() && addresses != null && !addresses.isEmpty();
    }
    
    /**
     * Refresh the expiration time (extend by EXPIRATION_HOURS from now)
     */
    public void refresh() {
        this.expiresAt = LocalDateTime.now().plusHours(EXPIRATION_HOURS);
    }
    
    /**
     * Get time remaining until expiration in minutes
     */
    public long getMinutesUntilExpiration() {
        if (isExpired()) {
            return 0;
        }
        return java.time.Duration.between(LocalDateTime.now(), expiresAt).toMinutes();
    }
}
