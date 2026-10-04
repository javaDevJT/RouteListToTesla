package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Service for caching processed OCR/geocoding results based on image SHA hashes.
 * Prevents reprocessing of identical images for better performance and UX.
 */
@Service
public class ImageCacheService {

    private final Path cacheDirectory;
    private final Path cacheIndexFile;
    private static final Logger log = org.slf4j.LoggerFactory.getLogger(ImageCacheService.class);
    private final ObjectMapper objectMapper = JsonMapper.builderWithJackson2Defaults().build();
    @Value("${ocr.language:eng}")
    private String ocrLanguage = "eng";

    public ImageCacheService() {
        this(Paths.get("cache"));
    }

    ImageCacheService(Path root) {
        cacheDirectory = root.resolve("images");
        cacheIndexFile = root.resolve("image_cache_index.json");
        initializeCacheDirectory();
    }

    private void initializeCacheDirectory() {
        try {
            Files.createDirectories(cacheDirectory);
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

            // Kept for compatibility; the SHA-256 digest already fills all 64 characters.
            String combined = hexString.toString() + "_" + filename.replaceAll("[^a-zA-Z0-9._-]", "_");
            return combined.substring(0, Math.min(64, combined.length())); // Limit length
        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 algorithm not available", e);
            return "fallback_" + System.currentTimeMillis() + "_" + filename.hashCode();
        }
    }

    public String calculateImageHash(byte[] imageBytes, String filename, String defaultState, String ownerSub) {
        return calculateClientCacheKey(calculateImageHash(imageBytes, filename), defaultState, ownerSub);
    }

    public String calculateClientCacheKey(String contentHash, String defaultState, String ownerSub) {
        if (contentHash == null || !contentHash.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Content hash must be a SHA-256 hex digest");
        }
        if (ownerSub == null || ownerSub.isBlank()) {
            throw new IllegalArgumentException("An account owner is required for image caching");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(AddressOcrService.EXTRACTION_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(GeocodingClient.GEOCODING_VERSION.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            String language = ocrLanguage == null || ocrLanguage.isBlank() ? "eng" : ocrLanguage.trim();
            digest.update(language.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((defaultState == null ? "" : defaultState).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(ownerSub.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(contentHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm is unavailable", e);
        }
    }

    /**
     * Cache processed results for an image
     */
    // ponytail: serialize short JSON writes; use a database if cache write volume becomes limiting.
    public synchronized void cacheImageResults(String imageHash, String originalFilename, List<PlaceCandidate> candidates) {
        try {
            CacheEntry entry = new CacheEntry();
            entry.imageHash = imageHash;
            entry.originalFilename = originalFilename;
            entry.candidates = candidates;
            entry.timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            entry.candidateCount = candidates.size();

            // Save individual cache entry
            Path cacheFile = cacheDirectory.resolve(imageHash + ".json");
            writeCacheFile(cacheFile, entry);

            // Update cache index
            updateCacheIndex(imageHash, entry);

            log.info("Cached results for image {} (hash: {}) with {} candidates",
                    originalFilename, imageHash, candidates.size());

        } catch (IOException | JacksonException e) {
            log.warn("Failed to cache image results for {}: {}", originalFilename, e.getMessage());
        }
    }

    /**
     * Retrieve cached results for an image hash
     */
    public List<PlaceCandidate> getCachedResults(String imageHash) {
        try {
            Path cacheFile = cacheDirectory.resolve(imageHash + ".json");
            if (!Files.exists(cacheFile)) {
                return null;
            }

            CacheEntry entry = objectMapper.readValue(cacheFile.toFile(), CacheEntry.class);
            log.info("Retrieved cached results for hash {} with {} candidates",
                    imageHash, entry.candidates.size());

            return entry.candidates;

        } catch (JacksonException e) {
            log.warn("Failed to read cached results for hash {}: {}", imageHash, e.getMessage());
            return null;
        }
    }

    /**
     * Check if results are cached for this image hash
     */
    public boolean isCached(String imageHash) {
        Path cacheFile = cacheDirectory.resolve(imageHash + ".json");
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

            writeCacheFile(cacheIndexFile, index);

        } catch (IOException | JacksonException e) {
            log.warn("Failed to update cache index: {}", e.getMessage());
        }
    }

    /**
     * Load the cache index
     */
    private Map<String, CacheIndexEntry> loadCacheIndex() {
        try {
            File indexFile = cacheIndexFile.toFile();
            if (indexFile.exists()) {
                return objectMapper.readValue(indexFile, new TypeReference<>() {
                });
            }
        } catch (JacksonException e) {
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
        stats.put("totalCachedImages", index.keySet().stream().filter(key -> !key.endsWith(".ocr")).count());
        stats.put("cachedExtractions", index.keySet().stream().filter(key -> key.endsWith(".ocr")).count());
        stats.put("cacheDirectory", cacheDirectory.toString());
        stats.put("indexFile", cacheIndexFile.toString());
        return stats;
    }

    /**
     * Clear old cache entries (could be scheduled)
     */
    public synchronized void clearOldEntries(int daysOld) {
        Map<String, CacheIndexEntry> index = loadCacheIndex();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(daysOld);

        index.entrySet().removeIf(entry -> {
            try {
                LocalDateTime entryTime = LocalDateTime.parse(entry.getValue().timestamp, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                if (entryTime.isBefore(cutoff)) {
                    // Delete cache file
                    Path cacheFile = cacheDirectory.resolve(entry.getKey() + ".json");
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
            writeCacheFile(cacheIndexFile, index);
        } catch (IOException | JacksonException e) {
            log.warn("Failed to save updated cache index: {}", e.getMessage());
        }
    }

    public void cacheOcrResults(String imageHash, String originalFilename, List<PlaceCandidate> candidates) {
        cacheImageResults(imageHash + ".ocr", originalFilename, candidates);
    }

    public List<PlaceCandidate> getCachedOcrResults(String imageHash) {
        return getCachedResults(imageHash + ".ocr");
    }

    private void writeCacheFile(Path target, Object value) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".cache-", ".tmp");
        try {
            objectMapper.writeValue(temporary.toFile(), value);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
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
