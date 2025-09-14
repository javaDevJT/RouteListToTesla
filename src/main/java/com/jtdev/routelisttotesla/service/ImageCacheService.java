package com.jtdev.routelisttotesla.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service for caching processed OCR/geocoding results based on image SHA hashes.
 * Prevents reprocessing of identical images for better performance and UX.
 */
@Service
public class ImageCacheService {

    private static final String CACHE_DIR = "cache/images";
    private static final String CACHE_INDEX_FILE = "cache/image_cache_index.json";
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ImageCacheService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ImageCacheService() {
        initializeCacheDirectory();
    }

    private void initializeCacheDirectory() {
        try {
            Files.createDirectories(Paths.get(CACHE_DIR));
            Files.createDirectories(Paths.get("cache"));
        } catch (IOException e) {
            log.warn("Failed to create cache directory: {}", e.getMessage());
        }
    }

    /**
     * Calculate SHA-256 hash of image bytes
     */
    public String calculateImageHash(byte[] imageBytes, String filename) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(imageBytes);
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            // Include filename in hash to handle cases where same content has different names
            String combined = hexString.toString() + "_" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            return combined.substring(0, Math.min(64, combined.length())); // Limit length
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            return "fallback_" + System.currentTimeMillis() + "_" + filename.hashCode();
        }
    }

    /**
     * Cache processed results for an image
     */
    public void cacheImageResults(String imageHash, String originalFilename, List<PlaceCandidate> candidates) {
        try {
            CacheEntry entry = new CacheEntry();
            entry.imageHash = imageHash;
            entry.originalFilename = originalFilename;
            entry.candidates = candidates;
            entry.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            entry.candidateCount = candidates.size();

            // Save individual cache entry
            Path cacheFile = Paths.get(CACHE_DIR, imageHash + ".json");
            objectMapper.writeValue(cacheFile.toFile(), entry);

            // Update cache index
            updateCacheIndex(imageHash, entry);

            log.info("Cached results for image {} (hash: {}) with {} candidates",
                    originalFilename, imageHash, candidates.size());

        } catch (IOException e) {
            log.warn("Failed to cache image results for {}: {}", originalFilename, e.getMessage());
        }
    }

    /**
     * Retrieve cached results for an image hash
     */
    public List<PlaceCandidate> getCachedResults(String imageHash) {
        try {
            Path cacheFile = Paths.get(CACHE_DIR, imageHash + ".json");
            if (!Files.exists(cacheFile)) {
                return null;
            }

            CacheEntry entry = objectMapper.readValue(cacheFile.toFile(), CacheEntry.class);
            log.info("Retrieved cached results for hash {} with {} candidates",
                    imageHash, entry.candidates.size());

            return entry.candidates;

        } catch (IOException e) {
            log.warn("Failed to read cached results for hash {}: {}", imageHash, e.getMessage());
            return null;
        }
    }

    /**
     * Check if results are cached for this image hash
     */
    public boolean isCached(String imageHash) {
        Path cacheFile = Paths.get(CACHE_DIR, imageHash + ".json");
        return Files.exists(cacheFile);
    }

    /**
     * Update the cache index for tracking and management
     */
    private void updateCacheIndex(String imageHash, CacheEntry entry) {
        try {
            Map<String, CacheIndexEntry> index = loadCacheIndex();

            CacheIndexEntry indexEntry = new CacheIndexEntry();
            indexEntry.filename = entry.originalFilename;
            indexEntry.timestamp = entry.timestamp;
            indexEntry.candidateCount = entry.candidateCount;

            index.put(imageHash, indexEntry);

            objectMapper.writeValue(new File(CACHE_INDEX_FILE), index);

        } catch (IOException e) {
            log.warn("Failed to update cache index: {}", e.getMessage());
        }
    }

    /**
     * Load the cache index
     */
    private Map<String, CacheIndexEntry> loadCacheIndex() {
        try {
            File indexFile = new File(CACHE_INDEX_FILE);
            if (indexFile.exists()) {
                return objectMapper.readValue(indexFile, new TypeReference<>() {
                });
            }
        } catch (IOException e) {
            log.warn("Failed to load cache index: {}", e.getMessage());
        }
        return new HashMap<>();
    }

    /**
     * Get cache statistics for monitoring
     */
    public Map<String, Object> getCacheStats() {
        Map<String, CacheIndexEntry> index = loadCacheIndex();
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCachedImages", index.size());
        stats.put("cacheDirectory", CACHE_DIR);
        stats.put("indexFile", CACHE_INDEX_FILE);
        return stats;
    }

    /**
     * Clear old cache entries (could be scheduled)
     */
    public void clearOldEntries(int daysOld) {
        Map<String, CacheIndexEntry> index = loadCacheIndex();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);

        index.entrySet().removeIf(entry -> {
            try {
                LocalDateTime entryTime = LocalDateTime.parse(entry.getValue().timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (entryTime.isBefore(cutoff)) {
                    // Delete cache file
                    Path cacheFile = Paths.get(CACHE_DIR, entry.getKey() + ".json");
                    Files.deleteIfExists(cacheFile);
                    log.info("Cleaned up old cache entry: {}", entry.getKey());
                    return true;
                }
            } catch (Exception e) {
                log.warn("Error processing cache entry {}: {}", entry.getKey(), e.getMessage());
            }
            return false;
        });

        try {
            objectMapper.writeValue(new File(CACHE_INDEX_FILE), index);
        } catch (IOException e) {
            log.warn("Failed to save updated cache index: {}", e.getMessage());
        }
    }

    // Cache entry data structures
    public static class CacheEntry {
        public String imageHash;
        public String originalFilename;
        public List<PlaceCandidate> candidates;
        public String timestamp;
        public int candidateCount;
    }

    public static class CacheIndexEntry {
        public String filename;
        public String timestamp;
        public int candidateCount;
    }
}
