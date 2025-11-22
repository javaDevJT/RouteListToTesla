package com.jtdev.routelisttotesla.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jtdev.routelisttotesla.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing user sessions for session recovery.
 * Allows users to reload their previous work within 8 hours without re-uploading images.
 */
@Service
public class UserSessionService {
    
    private static final Logger log = LoggerFactory.getLogger(UserSessionService.class);
    private static final String SESSIONS_DIR = "cache/user-sessions";
    
    private final ObjectMapper objectMapper;
    
    public UserSessionService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        initializeSessionsDirectory();
    }
    
    private void initializeSessionsDirectory() {
        try {
            Files.createDirectories(Paths.get(SESSIONS_DIR));
        } catch (IOException e) {
            log.warn("Failed to create user sessions directory: {}", e.getMessage());
        }
    }
    
    /**
     * Generate a safe filename from user ID (email)
     */
    private String sanitizeUserId(String userId) {
        return userId.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }
    
    /**
     * Get the session file path for a user
     */
    private Path getSessionFilePath(String userId) {
        return Paths.get(SESSIONS_DIR, sanitizeUserId(userId) + ".json");
    }
    
    /**
     * Save or update a user session
     */
    public void saveSession(UserSession session) {
        try {
            Path sessionFile = getSessionFilePath(session.getUserId());
            objectMapper.writeValue(sessionFile.toFile(), session);
            log.info("Saved session for user {} with {} addresses", 
                    session.getUserId(), session.getAddresses().size());
        } catch (IOException e) {
            log.warn("Failed to save session for user {}: {}", session.getUserId(), e.getMessage());
        }
    }
    
    /**
     * Load a user's session if it exists and is valid
     */
    public UserSession loadSession(String userId) {
        try {
            Path sessionFile = getSessionFilePath(userId);
            if (!Files.exists(sessionFile)) {
                return null;
            }
            
            UserSession session = objectMapper.readValue(sessionFile.toFile(), UserSession.class);
            
            // Check if expired
            if (session.isExpired()) {
                log.info("Session for user {} has expired, deleting", userId);
                deleteSession(userId);
                return null;
            }
            
            log.info("Loaded session for user {} with {} addresses (expires in {} minutes)", 
                    userId, session.getAddresses().size(), session.getMinutesUntilExpiration());
            
            return session;
            
        } catch (IOException e) {
            log.warn("Failed to load session for user {}: {}", userId, e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if a valid session exists for a user
     */
    public boolean hasValidSession(String userId) {
        UserSession session = loadSession(userId);
        return session != null && session.isValid();
    }
    
    /**
     * Get session info without loading all data (for UI checks)
     */
    public Map<String, Object> getSessionInfo(String userId) {
        UserSession session = loadSession(userId);
        Map<String, Object> info = new HashMap<>();
        
        if (session != null && session.isValid()) {
            info.put("valid", true);
            info.put("addressCount", session.getAddresses().size());
            info.put("vin", session.getVin());
            info.put("defaultState", session.getDefaultState());
            info.put("minutesRemaining", session.getMinutesUntilExpiration());
            info.put("activeAutoNavSessionId", session.getActiveAutoNavSessionId());
            info.put("createdAt", session.getCreatedAt().toString());
        } else {
            info.put("valid", false);
        }
        
        return info;
    }
    
    /**
     * Update the active auto-nav session ID for a user
     */
    public void setActiveAutoNavSession(String userId, String autoNavSessionId) {
        UserSession session = loadSession(userId);
        if (session != null) {
            session.setActiveAutoNavSessionId(autoNavSessionId);
            saveSession(session);
            log.info("Set active auto-nav session {} for user {}", autoNavSessionId, userId);
        }
    }
    
    /**
     * Clear the active auto-nav session ID for a user
     */
    public void clearActiveAutoNavSession(String userId) {
        UserSession session = loadSession(userId);
        if (session != null) {
            session.setActiveAutoNavSessionId(null);
            saveSession(session);
            log.info("Cleared active auto-nav session for user {}", userId);
        }
    }
    
    /**
     * Refresh a session's expiration time
     */
    public void refreshSession(String userId) {
        UserSession session = loadSession(userId);
        if (session != null) {
            session.refresh();
            saveSession(session);
            log.info("Refreshed session for user {}", userId);
        }
    }
    
    /**
     * Delete a user's session
     */
    public boolean deleteSession(String userId) {
        try {
            Path sessionFile = getSessionFilePath(userId);
            boolean deleted = Files.deleteIfExists(sessionFile);
            if (deleted) {
                log.info("Deleted session for user {}", userId);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("Failed to delete session for user {}: {}", userId, e.getMessage());
            return false;
        }
    }
    
    /**
     * Clean up expired sessions (could be scheduled)
     */
    public void cleanupExpiredSessions() {
        try {
            File sessionsDir = new File(SESSIONS_DIR);
            File[] files = sessionsDir.listFiles((dir, name) -> name.endsWith(".json"));
            
            if (files != null) {
                int deleted = 0;
                for (File file : files) {
                    try {
                        UserSession session = objectMapper.readValue(file, UserSession.class);
                        if (session.isExpired()) {
                            if (file.delete()) {
                                deleted++;
                            }
                        }
                    } catch (IOException e) {
                        log.warn("Failed to check session file {}: {}", file.getName(), e.getMessage());
                    }
                }
                if (deleted > 0) {
                    log.info("Cleaned up {} expired sessions", deleted);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to cleanup expired sessions: {}", e.getMessage());
        }
    }
}
