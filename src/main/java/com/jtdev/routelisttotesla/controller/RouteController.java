package com.jtdev.routelisttotesla.controller;


import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.FleetApi;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.PlaceCandidatesResponse;
import com.jtdev.routelisttotesla.model.UserSession;
import com.jtdev.routelisttotesla.service.AddressOcrService;
import com.jtdev.routelisttotesla.service.AutoNavigationService;
import com.jtdev.routelisttotesla.service.GeocodingClient;
import com.jtdev.routelisttotesla.service.ImageCacheService;
import com.jtdev.routelisttotesla.service.UserSessionService;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/route")
@Validated
public class RouteController {
    private static final Logger log = LoggerFactory.getLogger(RouteController.class);
    
    private final AddressOcrService ocr;
    private final GeocodingClient geocoder;
    private final ImageCacheService cacheService;
    private final AutoNavigationService autoNavigationService;
    private final UserSessionService userSessionService;

    @Autowired
    FleetApi fleetApi;

    public RouteController(AddressOcrService ocr, GeocodingClient geocoder, ImageCacheService cacheService,
                          AutoNavigationService autoNavigationService, UserSessionService userSessionService) {
        this.ocr = ocr; 
        this.geocoder = geocoder;
        this.cacheService = cacheService;
        this.autoNavigationService = autoNavigationService;
        this.userSessionService = userSessionService;
    }

    @PostMapping(value = "/places", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PlaceCandidatesResponse places(@RequestPart("images") @NotEmpty MultipartFile[] images, @RequestParam(value = "defaultState", defaultValue = "") String defaultState) throws Exception {
        // EXACTLY replicate the original logic with transparent caching
        
        // 1) OCR all images (with cache optimization)
        List<PlaceCandidate> candidates = new ArrayList<>();
        images = Arrays.stream(images).sorted(Comparator.comparing(x -> x.getOriginalFilename())).toArray(MultipartFile[]::new);
        Map<String, List<PlaceCandidate>> imageToCachedResults = new HashMap<>();
        
        for (MultipartFile f : images) {
            String filename = Objects.requireNonNull(f.getOriginalFilename());
            byte[] imageBytes = f.getBytes();
            String imageHash = cacheService.calculateImageHash(imageBytes, filename);
            
            // Check cache first
            List<PlaceCandidate> cachedResults = cacheService.getCachedResults(imageHash);
            if (cachedResults != null) {
                log.info("Using cached results for image {} (hash: {})", filename, imageHash);
                // Extract OCR-like candidates from cache (strip geocoding to match original OCR output)
                List<PlaceCandidate> ocrCandidates = cachedResults.stream()
                    .map(candidate -> new PlaceCandidate(
                        candidate.text(), 
                        candidate.normalized(), 
                        candidate.sourceImage(), 
                        candidate.lineIndex(),
                        0, 0, null  // Strip geocoding to match original OCR
                    ))
                    .toList();
                candidates.addAll(ocrCandidates);
                imageToCachedResults.put(imageHash, cachedResults);
            } else {
            log.info("Processing new image {} (hash: {})", filename, imageHash);
            List<PlaceCandidate> ocrResults = ocr.extractAddressCandidates(imageBytes, filename, defaultState);
            candidates.addAll(ocrResults);
            imageToCachedResults.put(imageHash, null);
            }
        }

        // 2) Normalize + de-duplicate by normalized text (EXACT original logic)
        LinkedHashMap<String, PlaceCandidate> uniq = new LinkedHashMap<>();
        for (PlaceCandidate c : candidates) {
            uniq.putIfAbsent(c.normalized(), c);
        }

        // 3) Geocode each unique candidate (with cache optimization)
        List<PlaceCandidate> uniqueCandidates = new ArrayList<>(uniq.values());
        List<PlaceCandidate> resolved = new ArrayList<>();
        
        for (PlaceCandidate candidate : uniqueCandidates) {
            // Check if we have cached geocoding for this candidate
            String imageHash = null;
            for (MultipartFile f : images) {
                if (Objects.equals(f.getOriginalFilename(), candidate.sourceImage())) {
                    imageHash = cacheService.calculateImageHash(f.getBytes(), f.getOriginalFilename());
                    break;
                }
            }
            
            List<PlaceCandidate> cachedResults = imageToCachedResults.get(imageHash);
            if (cachedResults != null) {
                // Find matching geocoded result from cache
                PlaceCandidate geocoded = cachedResults.stream()
                    .filter(c -> c.normalized().equals(candidate.normalized()))
                    .findFirst()
                    .orElse(null);
                if (geocoded != null) {
                    resolved.add(geocoded);
                    continue;
                }
            }
            
            // No cache hit - need to geocode this candidate
            List<PlaceCandidate> geocodedList = geocoder.batchGeocode(List.of(candidate));
            if (!geocodedList.isEmpty()) {
                resolved.add(geocodedList.get(0));
            }
        }

        // 4) Cache results for each image that wasn't already cached
        Map<String, List<PlaceCandidate>> resultsByImage = new HashMap<>();
        for (PlaceCandidate result : resolved) {
            resultsByImage.computeIfAbsent(result.sourceImage(), k -> new ArrayList<>()).add(result);
        }
        
        for (MultipartFile f : images) {
            String filename = f.getOriginalFilename();
            String imageHash = cacheService.calculateImageHash(f.getBytes(), filename);
            
            if (imageToCachedResults.get(imageHash) == null && resultsByImage.containsKey(filename)) {
                // Cache the geocoded results for this image
                cacheService.cacheImageResults(imageHash, filename, resultsByImage.get(filename));
            }
        }

        return new PlaceCandidatesResponse(resolved);
    }

    @PostMapping(value = "/places/{vin}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public boolean sendToTesla(@PathVariable String vin, @RequestPart("images") @NotEmpty MultipartFile[] images) throws Exception {
    PlaceCandidatesResponse response = places(images, "");
//        var index = 1;
//        for (PlaceCandidate c : response.candidates()) {
//            Map<String, Object> requestMap = Map.of("lat", c.lat(), "lon", c.lon(), "order", index++);
//          fleetApi.commandNavigationGpsRequest(vin, requestMap);
//            Thread.sleep(5000);
//        }
//        return true;
        return fleetApi.commandNavigationWaypointsRequest(vin, Map.of("waypoints", response.candidates().stream().map(x -> "refId:" + x.pid()).collect(Collectors.joining(",")))).contains("true");

    }

    @PostMapping(value = "/send/{vin}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public boolean sendEditedAddressesToTesla(@PathVariable String vin, @RequestBody PlaceCandidatesResponse addressData) throws Exception {
        List<PlaceCandidate> candidates = addressData.candidates();
        
        // Filter out candidates without text
        candidates = candidates.stream()
                .filter(c -> c.text() != null && !c.text().trim().isEmpty())
                .collect(Collectors.toList());
        
        if (candidates.isEmpty()) {
            return false;
        }
        
        // Re-geocode addresses that were manually edited (have no lat/lon or pid)
        List<PlaceCandidate> toGeocode = candidates.stream()
                .filter(c -> c.lat() == 0 || c.lon() == 0 || c.pid() == null)
                .collect(Collectors.toList());
        
        if (!toGeocode.isEmpty()) {
            List<PlaceCandidate> geocoded = geocoder.batchGeocode(toGeocode);
            
            // Update the original candidates list with geocoded results
            Map<String, PlaceCandidate> geocodedMap = geocoded.stream()
                    .collect(Collectors.toMap(c -> c.text().trim(), c -> c, (a, b) -> a));
            
            candidates = candidates.stream()
                    .map(c -> {
                        if (c.lat() == 0 || c.lon() == 0 || c.pid() == null) {
                            return geocodedMap.getOrDefault(c.text().trim(), c);
                        }
                        return c;
                    })
                    .filter(c -> c.lat() != 0 && c.lon() != 0 && c.pid() != null)
                    .collect(Collectors.toList());
        }
        
        if (candidates.isEmpty()) {
            return false;
        }
        
        return fleetApi.commandNavigationWaypointsRequest(vin, 
            Map.of("waypoints", candidates.stream()
                .map(x -> "refId:" + x.pid())
                .collect(Collectors.joining(",")))
        ).contains("true");
    }

    @PostMapping(value = "/cache/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> checkImageCache(@RequestBody Map<String, String> request) {
        String imageHash = request.get("imageHash");
        String filename = request.get("filename");
        
        if (imageHash == null || imageHash.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<PlaceCandidate> cachedResults = cacheService.getCachedResults(imageHash);
        
        Map<String, Object> response = new HashMap<>();
        response.put("cached", cachedResults != null);
        response.put("imageHash", imageHash);
        response.put("filename", filename);
        
        if (cachedResults != null) {
            response.put("candidates", cachedResults);
        }
        
        return ResponseEntity.ok(response);
    }

    // ==================================================================================
    //                           AUTO-NAVIGATION ENDPOINTS
    // ==================================================================================

    /**
     * Start automatic navigation - creates a session and begins monitoring the vehicle
     */
    @PostMapping(value = "/auto-navigate/{vin}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> startAutoNavigation(
            @PathVariable String vin,
            @RequestBody PlaceCandidatesResponse addressData,
            @AuthenticationPrincipal OAuth2User principal) {
        
        String userId = getUserId(principal);
        List<PlaceCandidate> candidates = addressData.candidates();
        
        // Filter out empty addresses
        candidates = candidates.stream()
                .filter(c -> c.text() != null && !c.text().trim().isEmpty())
                .collect(Collectors.toList());
        
        if (candidates.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid addresses provided"));
        }
        
        try {
            // Create and start the session
            AutoNavSession session = autoNavigationService.createSession(vin, userId, candidates);
            autoNavigationService.startSession(session.getSessionId());
            
            // Store the session ID in user session for recovery
            userSessionService.setActiveAutoNavSession(userId, session.getSessionId());
            
            Map<String, Object> response = buildSessionStatusResponse(session);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to start auto-navigation: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get the status of an auto-navigation session
     */
    @GetMapping("/auto-navigate/status/{sessionId}")
    public ResponseEntity<Map<String, Object>> getAutoNavStatus(@PathVariable String sessionId) {
        AutoNavSession session = autoNavigationService.getSession(sessionId);
        
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> response = buildSessionStatusResponse(session);
        return ResponseEntity.ok(response);
    }

    /**
     * Get any active auto-navigation session for the current user
     */
    @GetMapping("/auto-navigate/active")
    public ResponseEntity<Map<String, Object>> getActiveAutoNavSession(@AuthenticationPrincipal OAuth2User principal) {
        String userId = getUserId(principal);
        AutoNavSession session = autoNavigationService.getActiveSessionForUser(userId);
        
        if (session == null) {
            return ResponseEntity.ok(Map.of("active", false));
        }
        
        Map<String, Object> response = buildSessionStatusResponse(session);
        response.put("active", true);
        return ResponseEntity.ok(response);
    }

    /**
     * Stop an auto-navigation session
     */
    @PostMapping("/auto-navigate/stop/{sessionId}")
    public ResponseEntity<Map<String, Object>> stopAutoNavigation(
            @PathVariable String sessionId,
            @AuthenticationPrincipal OAuth2User principal) {
        
        String userId = getUserId(principal);
        
        try {
            AutoNavSession session = autoNavigationService.stopSession(sessionId);
            
            // Clear the active session from user session
            userSessionService.clearActiveAutoNavSession(userId);
            
            Map<String, Object> response = buildSessionStatusResponse(session);
            return ResponseEntity.ok(response);
            
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to stop auto-navigation: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Build a standardized response for session status
     */
    private Map<String, Object> buildSessionStatusResponse(AutoNavSession session) {
        Map<String, Object> response = new HashMap<>();
        response.put("sessionId", session.getSessionId());
        response.put("vin", session.getVin());
        response.put("status", session.getStatus().name());
        response.put("currentGroupIndex", session.getCurrentGroupIndex());
        response.put("totalGroups", session.getTotalGroups());
        response.put("completedAddresses", session.getCompletedAddresses());
        response.put("totalAddresses", session.getAllAddresses().size());
        response.put("progressPercentage", session.getProgressPercentage());
        response.put("lastError", session.getLastError());
        
        if (session.getLastPollAt() != null) {
            response.put("lastPollAt", session.getLastPollAt().toString());
        }
        if (session.getUpdatedAt() != null) {
            response.put("updatedAt", session.getUpdatedAt().toString());
        }
        
        // Add timing information for better UI status display
        if (session.getLastRouteSentAt() != null) {
            response.put("lastRouteSentAt", session.getLastRouteSentAt().toString());
            response.put("lastGroupSize", session.getLastGroupSize());
            
            // Calculate minutes until next check (2 min per stop estimate)
            int lastGroupSize = Math.max(1, session.getLastGroupSize());
            int minimumMinutes = lastGroupSize * 2; // MINUTES_PER_STOP = 2
            java.time.LocalDateTime earliestNextCheck = session.getLastRouteSentAt().plusMinutes(minimumMinutes);
            long secondsRemaining = java.time.Duration.between(java.time.LocalDateTime.now(), earliestNextCheck).getSeconds();
            long minutesRemaining = Math.max(0, (secondsRemaining + 59) / 60);
            response.put("minutesUntilNextCheck", minutesRemaining);
            response.put("waitingForMinTime", secondsRemaining > 0);
        } else {
            response.put("minutesUntilNextCheck", 0);
            response.put("waitingForMinTime", false);
        }
        
        return response;
    }

    // ==================================================================================
    //                           SESSION RECOVERY ENDPOINTS
    // ==================================================================================

    /**
     * Save the current session data for later recovery
     */
    @PostMapping(value = "/session/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveSession(
            @RequestBody Map<String, Object> sessionData,
            @AuthenticationPrincipal OAuth2User principal) {
        
        String userId = getUserId(principal);
        
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> candidatesData = (List<Map<String, Object>>) sessionData.get("candidates");
            @SuppressWarnings("unchecked")
            List<String> imageHashes = (List<String>) sessionData.get("imageHashes");
            String vin = (String) sessionData.get("vin");
            String defaultState = (String) sessionData.get("defaultState");
            
            // Convert candidates data to PlaceCandidate objects
            List<PlaceCandidate> candidates = candidatesData.stream()
                    .map(this::mapToPlaceCandidate)
                    .collect(Collectors.toList());
            
            UserSession session = new UserSession(userId, vin, defaultState, candidates, imageHashes);
            userSessionService.saveSession(session);
            
            Map<String, Object> response = new HashMap<>();
            response.put("saved", true);
            response.put("addressCount", candidates.size());
            response.put("expiresIn", session.getMinutesUntilExpiration() + " minutes");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Failed to save session: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Load a previously saved session
     */
    @GetMapping("/session/load")
    public ResponseEntity<Map<String, Object>> loadSession(@AuthenticationPrincipal OAuth2User principal) {
        String userId = getUserId(principal);
        
        UserSession session = userSessionService.loadSession(userId);
        
        if (session == null || !session.isValid()) {
            return ResponseEntity.ok(Map.of("valid", false));
        }
        
        Map<String, Object> response = new HashMap<>();
        response.put("valid", true);
        response.put("vin", session.getVin());
        response.put("defaultState", session.getDefaultState());
        response.put("candidates", session.getAddresses());
        response.put("imageHashes", session.getImageHashes());
        response.put("minutesRemaining", session.getMinutesUntilExpiration());
        response.put("activeAutoNavSessionId", session.getActiveAutoNavSessionId());
        
        return ResponseEntity.ok(response);
    }

    /**
     * Check if a valid session exists (lightweight check)
     */
    @GetMapping("/session/check")
    public ResponseEntity<Map<String, Object>> checkSession(@AuthenticationPrincipal OAuth2User principal) {
        String userId = getUserId(principal);
        Map<String, Object> info = userSessionService.getSessionInfo(userId);
        return ResponseEntity.ok(info);
    }

    /**
     * Delete the current session
     */
    @DeleteMapping("/session")
    public ResponseEntity<Map<String, Object>> deleteSession(@AuthenticationPrincipal OAuth2User principal) {
        String userId = getUserId(principal);
        boolean deleted = userSessionService.deleteSession(userId);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Helper to convert map to PlaceCandidate
     */
    private PlaceCandidate mapToPlaceCandidate(Map<String, Object> data) {
        String text = (String) data.get("text");
        String normalized = (String) data.get("normalized");
        String sourceImage = (String) data.get("sourceImage");
        int lineIndex = data.get("lineIndex") != null ? ((Number) data.get("lineIndex")).intValue() : 0;
        double lat = data.get("lat") != null ? ((Number) data.get("lat")).doubleValue() : 0;
        double lon = data.get("lon") != null ? ((Number) data.get("lon")).doubleValue() : 0;
        String pid = (String) data.get("pid");
        
        return new PlaceCandidate(text, normalized, sourceImage, lineIndex, lat, lon, pid);
    }

    /**
     * Get user ID from OAuth principal
     */
    private String getUserId(OAuth2User principal) {
        if (principal == null) {
            throw new IllegalStateException("User not authenticated");
        }
        String email = principal.getAttribute("email");
        if (email == null) {
            email = principal.getName();
        }
        return email;
    }
}
