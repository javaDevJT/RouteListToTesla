package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.jtdev.routelisttotesla.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
    private final Path sessionsDirectory;
    
    private final ObjectMapper objectMapper;
    
    public UserSessionService() {
        this(Paths.get("cache/user-sessions"));
    }

    UserSessionService(Path sessionsDirectory) {
        this.sessionsDirectory = sessionsDirectory;
        this.objectMapper = JsonMapper.builderWithJackson2Defaults()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        initializeSessionsDirectory();
    }
    
    private void initializeSessionsDirectory() {
        try {
            Files.createDirectories(sessionsDirectory);
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
        return sessionsDirectory.resolve(sanitizeUserId(userId) + ".json");
    }
    
    /**
     * Save or update a user session
     */
    public synchronized void saveSession(UserSession session) {
        try {
            Path sessionFile = getSessionFilePath(session.getUserId());
            objectMapper.writeValue(sessionFile.toFile(), session);
            log.info("Saved session for user {} with {} addresses", 
                    session.getUserId(), session.getAddresses().size());
        } catch (JacksonException e) {
            log.warn("Failed to save session for user {}: {}", session.getUserId(), e.getMessage());
        }
    }

    /** Called only for an operator-bound legacy owner after that exact TAP subject signs in. */
    public synchronized void migrateOwner(String legacyEmail, String subject) {
        Path legacy = getSessionFilePath(legacyEmail);
        Path target = getSessionFilePath(subject);
        if (!Files.exists(legacy) || Files.exists(target) || legacy.equals(target)) return;
        Path temporary = null;
        try {
            UserSession session = objectMapper.readValue(legacy.toFile(), UserSession.class);
            if (!legacyEmail.equals(session.getUserId()) || session.isExpired()) return;
            session.setUserId(subject);
            temporary = Files.createTempFile(target.getParent(), "migration-", ".tmp");
            objectMapper.writeValue(temporary.toFile(), session);
            // Keep the original as a rollback copy; never overwrite an existing TAP-owned session.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("Could not preserve the existing saved route during TAP migration", e);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
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
            
        } catch (JacksonException e) {
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
        File sessionsDir = sessionsDirectory.toFile();
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
                    } catch (JacksonException e) {
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
