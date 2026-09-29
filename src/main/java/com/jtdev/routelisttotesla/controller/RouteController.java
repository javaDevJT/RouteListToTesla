package com.jtdev.routelisttotesla.controller;

import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.PlaceCandidatesResponse;
import com.jtdev.routelisttotesla.model.UserSession;
import com.jtdev.routelisttotesla.service.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.text.Normalizer;

@RestController
@RequestMapping("/route")
public class RouteController {
    private final AddressOcrService ocr;
    private final GeocodingClient geocoder;
    private final ImageCacheService cacheService;
    private final AutoNavigationService autoNavigationService;
    private final UserSessionService userSessionService;
    private final TapVehicleClient vehicles;
    private final TapAccessService tapAccessService;

    public RouteController(AddressOcrService ocr, GeocodingClient geocoder, ImageCacheService cacheService,
                           AutoNavigationService autoNavigationService, UserSessionService userSessionService,
                           TapVehicleClient vehicles, TapAccessService tapAccessService) {
        this.ocr = ocr;
        this.geocoder = geocoder;
        this.cacheService = cacheService;
        this.autoNavigationService = autoNavigationService;
        this.userSessionService = userSessionService;
        this.vehicles = vehicles;
        this.tapAccessService = tapAccessService;
    }

    @GetMapping("/vehicles")
    public List<TapVehicleClient.Vehicle> vehicles(@AuthenticationPrincipal OAuth2User principal) {
        return vehicles.vehicles(grantIdentity(principal));
    }

    @PostMapping(value = "/places", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PlaceCandidatesResponse places(@RequestPart("images") MultipartFile[] images,
                                          @RequestParam(value = "defaultState", defaultValue = "") String defaultState,
                                          @AuthenticationPrincipal OAuth2User principal) throws Exception {
        if (images == null || images.length == 0 || images.length > 30) {
            throw new IllegalArgumentException("Choose between 1 and 30 images");
        }
        TapAccessService.GrantIdentity identity = grantIdentity(principal);
        vehicles.requireAccess(identity);
        String owner = identity.ownerSub();
        String state = normalizeState(defaultState);
        List<PlaceCandidate> misses = new ArrayList<>();
        List<ImageWork> work = new ArrayList<>();
        // Multipart order is the explicit order reviewed in the browser. Repeated stops are intentional.
        for (MultipartFile file : images) {
            if (file.isEmpty() || file.getSize() > 12 * 1024 * 1024) {
                throw new IllegalArgumentException("Each image must be nonempty and no larger than 12 MB");
            }
            String filename = Objects.requireNonNullElse(file.getOriginalFilename(), "image");
            byte[] bytes = file.getBytes();
            String hash = cacheService.calculateImageHash(bytes, filename, state, owner);
            List<PlaceCandidate> cached = cacheService.getCachedResults(hash);
            if (cached == null) {
                List<PlaceCandidate> extracted = ocr.extractAddressCandidates(bytes, filename, state);
                int start = misses.size();
                misses.addAll(extracted);
                work.add(new ImageWork(filename, hash, null, start, misses.size()));
            } else {
                work.add(new ImageWork(filename, hash, cached, 0, 0));
            }
        }

        List<PlaceCandidate> geocoded = geocoder.batchGeocode(owner, misses);
        List<PlaceCandidate> results = new ArrayList<>();
        List<List<PlaceCandidate>> imageCandidates = new ArrayList<>();
        for (ImageWork image : work) {
            List<PlaceCandidate> candidates = image.cached() == null
                    ? geocoded.subList(image.start(), image.end()) : image.cached();
            if (image.cached() == null) cacheService.cacheImageResults(image.hash(), image.filename(), candidates);
            List<PlaceCandidate> rebound = rebindSource(candidates, image.filename());
            imageCandidates.add(rebound);
            appendImageCandidates(results, rebound);
        }
        return new PlaceCandidatesResponse(results, imageCandidates);
    }

    @PostMapping(value = "/places/{vin}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public void sendToTesla(@PathVariable String vin) {
        throw new ResponseStatusException(HttpStatus.GONE,
                "Extract with /route/places, review the addresses, then send with /route/send/{vin}");
    }

    @PostMapping(value = "/send/{vin}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TapVehicleClient.CommandResult> sendEditedAddressesToTesla(@PathVariable String vin,
                                              @RequestBody PlaceCandidatesResponse addressData,
                                              @RequestHeader("Idempotency-Key") String key,
                                              @AuthenticationPrincipal OAuth2User principal) throws Exception {
        TapAccessService.GrantIdentity identity = requireCommandAccess(vin, principal);
        if (key == null || !key.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("A stable route command idempotency key is required");
        }
        List<PlaceCandidate> candidates = resolveReviewed(addressData, 8, identity.ownerSub());
        TapVehicleClient.CommandResult result = autoNavigationService.sendManualRoute(identity, vin, candidates, key);
        return ResponseEntity.status("PENDING".equals(result.state()) ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result);
    }

    @PostMapping(value = "/cache/check", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> checkImageCache(@RequestBody Map<String, String> request,
                                              @AuthenticationPrincipal OAuth2User principal) {
        String contentHash = request.get("imageHash");
        if (contentHash == null || !contentHash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("A SHA-256 image hash is required");
        }
        String filename = Objects.requireNonNullElse(request.get("filename"), "image");
        String key = cacheService.calculateClientCacheKey(contentHash.toLowerCase(Locale.ROOT),
                normalizeState(request.get("defaultState")), getUserId(principal));
        List<PlaceCandidate> cached = cacheService.getCachedResults(key);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cached", cached != null);
        response.put("imageHash", contentHash);
        response.put("filename", filename);
        if (cached != null) response.put("candidates", rebindSource(cached, filename));
        return response;
    }

    @PostMapping(value = "/auto-navigate/{vin}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> startAutoNavigation(@PathVariable String vin,
                                                  @RequestBody PlaceCandidatesResponse addressData,
                                                  @AuthenticationPrincipal OAuth2User principal) throws Exception {
        TapAccessService.GrantIdentity identity = requireCommandAccess(vin, principal);
        String owner = identity.ownerSub();
        List<PlaceCandidate> candidates = resolveReviewed(addressData, 500, owner);
        AutoNavSession session = autoNavigationService.createSession(vin, identity, candidates);
        autoNavigationService.startSession(session.getSessionId(), identity);
        userSessionService.setActiveAutoNavSession(owner, session.getSessionId());
        return buildSessionStatusResponse(session);
    }

    @GetMapping("/auto-navigate/status/{sessionId}")
    public Map<String, Object> getAutoNavStatus(@PathVariable String sessionId,
                                                @AuthenticationPrincipal OAuth2User principal) {
        return buildSessionStatusResponse(requireOwnedSession(sessionId, grantIdentity(principal)));
    }

    @GetMapping("/auto-navigate/active")
    public Map<String, Object> getActiveAutoNavSession(@AuthenticationPrincipal OAuth2User principal) {
        TapAccessService.GrantIdentity identity = grantIdentity(principal);
        AutoNavSession session = autoNavigationService.getActiveSessionForUser(identity);
        if (session == null) return Map.of("active", false);
        Map<String, Object> response = buildSessionStatusResponse(session);
        response.put("active", true);
        return response;
    }

    @PostMapping("/auto-navigate/stop/{sessionId}")
    public Map<String, Object> stopAutoNavigation(@PathVariable String sessionId,
                                                 @AuthenticationPrincipal OAuth2User principal) {
        TapAccessService.GrantIdentity identity = grantIdentity(principal);
        String owner = identity.ownerSub();
        requireOwnedSession(sessionId, identity);
        AutoNavSession session = autoNavigationService.stopSession(sessionId, identity);
        userSessionService.clearActiveAutoNavSession(owner);
        return buildSessionStatusResponse(session);
    }

    private AutoNavSession requireOwnedSession(String id, TapAccessService.GrantIdentity identity) {
        AutoNavSession session = autoNavigationService.getSession(id, identity);
        if (session == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return session;
    }

    private Map<String, Object> buildSessionStatusResponse(AutoNavSession session) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.getSessionId());
        response.put("vin", session.getVin());
        response.put("status", session.getStatus().name());
        response.put("currentGroupIndex", session.getCurrentGroupIndex());
        response.put("currentWaypointIndex", session.getCurrentWaypointIndex());
        response.put("commandState", session.getCommandState() == null ? "UNKNOWN" : session.getCommandState().name());
        response.put("totalGroups", session.getTotalGroups());
        response.put("completedAddresses", session.getCompletedAddresses());
        response.put("totalAddresses", session.getAllAddresses().size());
        response.put("progressPercentage", session.getProgressPercentage());
        response.put("lastError", session.getLastError());
        if (session.getLastPollAt() != null) response.put("lastPollAt", session.getLastPollAt().toString());
        if (session.getUpdatedAt() != null) response.put("updatedAt", session.getUpdatedAt().toString());
        if (session.getLastRouteSentAt() != null) {
            response.put("lastRouteSentAt", session.getLastRouteSentAt().toString());
            response.put("lastGroupSize", session.getLastGroupSize());
        }
        return response;
    }

    @PostMapping(value = "/session/save", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> saveSession(@RequestBody SessionRequest request,
                                           @AuthenticationPrincipal OAuth2User principal) {
        if (request.candidates() == null || request.candidates().size() > 500
                || request.candidates().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("A session must contain at most 500 addresses");
        }
        if (request.imageHashes() != null && (request.imageHashes().size() > 30
                || request.imageHashes().stream().anyMatch(h -> h == null || !h.matches("[a-fA-F0-9]{64}")))) {
            throw new IllegalArgumentException("Invalid session image hashes");
        }
        UserSession session = new UserSession(getUserId(principal), request.vin(), normalizeState(request.defaultState()),
                request.candidates(), request.imageHashes() == null ? List.of() : request.imageHashes());
        userSessionService.saveSession(session);
        return Map.of("saved", true, "addressCount", request.candidates().size(), "expiresIn", session.getMinutesUntilExpiration() + " minutes");
    }

    @GetMapping("/session/load")
    public Map<String, Object> loadSession(@AuthenticationPrincipal OAuth2User principal) {
        UserSession session = userSessionService.loadSession(getUserId(principal));
        if (session == null || !session.isValid()) return Map.of("valid", false);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", true);
        response.put("vin", session.getVin());
        response.put("defaultState", session.getDefaultState());
        response.put("candidates", session.getAddresses());
        response.put("imageHashes", session.getImageHashes());
        response.put("minutesRemaining", session.getMinutesUntilExpiration());
        response.put("activeAutoNavSessionId", session.getActiveAutoNavSessionId());
        return response;
    }

    @GetMapping("/session/check")
    public Map<String, Object> checkSession(@AuthenticationPrincipal OAuth2User principal) {
        return userSessionService.getSessionInfo(getUserId(principal));
    }

    @DeleteMapping("/session")
    public Map<String, Object> deleteSession(@AuthenticationPrincipal OAuth2User principal) {
        return Map.of("deleted", userSessionService.deleteSession(getUserId(principal)));
    }

    private List<PlaceCandidate> resolveReviewed(PlaceCandidatesResponse request, int maximum, String owner) {
        if (request == null || request.candidates() == null || request.candidates().isEmpty()
                || request.candidates().size() > maximum) throw new IllegalArgumentException("Choose 1 to " + maximum + " reviewed addresses");
        List<PlaceCandidate> resolved = new ArrayList<>(request.candidates());
        List<PlaceCandidate> pending = new ArrayList<>();
        List<Integer> pendingIndexes = new ArrayList<>();
        for (int index = 0; index < request.candidates().size(); index++) {
            PlaceCandidate candidate = request.candidates().get(index);
            if (candidate == null || candidate.text() == null || candidate.text().isBlank() || candidate.text().length() > 1000) {
                throw new IllegalArgumentException("Every stop must have address text");
            }
            if (candidate.ocrReviewRequired()) {
                throw new IllegalArgumentException("Confirm or edit every unresolved OCR reading before sending a route");
            }
            if (!hasCoordinates(candidate) || candidate.pid() == null || candidate.pid().isBlank()) {
                pending.add(candidate);
                pendingIndexes.add(index);
            }
        }
        List<PlaceCandidate> geocoded = geocoder.batchGeocode(owner, pending);
        for (int i = 0; i < pendingIndexes.size(); i++) {
            resolved.set(pendingIndexes.get(i), geocoded.get(i));
        }
        for (PlaceCandidate candidate : resolved) {
            if (!hasCoordinates(candidate) || candidate.pid() == null || !candidate.pid().matches("[A-Za-z0-9_-]{1,512}")) {
                throw new IllegalArgumentException("Every stop needs valid coordinates and a Google place ID; unresolved stops cannot be skipped");
            }
        }
        return List.copyOf(resolved);
    }

    private boolean hasCoordinates(PlaceCandidate candidate) {
        return Double.isFinite(candidate.lat()) && Double.isFinite(candidate.lon())
                && Math.abs(candidate.lat()) <= 90 && Math.abs(candidate.lon()) <= 180
                && !(candidate.lat() == 0 && candidate.lon() == 0);
    }

    private void validateVin(String vin) {
        if (vin == null || !vin.matches("[A-Z0-9]{17}")) throw new IllegalArgumentException("An uppercase 17-character VIN is required");
    }

    private TapAccessService.GrantIdentity requireCommandAccess(String vin, OAuth2User principal) {
        validateVin(vin);
        TapAccessService.GrantIdentity identity = grantIdentity(principal);
        vehicles.requireAccess(identity);
        boolean allowed = vehicles.vehicles(identity).stream()
                .anyMatch(vehicle -> vin.equalsIgnoreCase(vehicle.vin()) && vehicle.command());
        if (!allowed) throw new AccessDeniedException("Command access to the selected vehicle is required");
        return identity;
    }

    private TapAccessService.GrantIdentity grantIdentity(OAuth2User principal) {
        if (principal == null) throw new AccessDeniedException("Sign in first");
        return tapAccessService.grantIdentity(principal);
    }

    private List<PlaceCandidate> rebindSource(List<PlaceCandidate> candidates, String filename) {
        return candidates.stream().map(c -> new PlaceCandidate(c.text(), c.normalized(), filename,
                c.lineIndex(), c.lat(), c.lon(), c.pid(), c.ocrAgreement(),
                c.ocrReviewRequired(), c.ocrAlternatives())).toList();
    }

    private void appendImageCandidates(List<PlaceCandidate> results, List<PlaceCandidate> incoming) {
        List<String> keys = incoming.stream().map(this::overlapKey).toList();
        List<String> existing = results.stream().map(this::overlapKey).toList();
        int start = -1;
        int overlap = 0;
        // Only contiguous screenshot overlap is removed; repeats inside an image remain stops.
        if (keys.size() > 1 && !keys.contains("")) {
            for (int index = 0; index + keys.size() <= existing.size(); index++) {
                if (existing.subList(index, index + keys.size()).equals(keys)) {
                    start = index;
                    overlap = keys.size();
                    break;
                }
            }
        }
        if (start < 0) {
            for (int size = Math.min(existing.size(), keys.size()); size > 0; size--) {
                List<String> prefix = keys.subList(0, size);
                if (!prefix.contains("") && existing.subList(existing.size() - size, existing.size()).equals(prefix)) {
                    start = existing.size() - size;
                    overlap = size;
                    break;
                }
            }
        }
        for (int index = 0; index < overlap; index++) {
            PlaceCandidate old = results.get(start + index);
            PlaceCandidate next = incoming.get(index);
            if (ocrQuality(next) > ocrQuality(old)) results.set(start + index, next);
        }
        results.addAll(incoming.subList(overlap, incoming.size()));
    }

    private String overlapKey(PlaceCandidate candidate) {
        String value = candidate.normalized();
        if (value == null || value.isBlank()) value = Objects.requireNonNullElse(candidate.text(), "");
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT)
                .replace(',', ' ').replaceAll("(?<![0-9])\\.|\\.(?![0-9])", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private int ocrQuality(PlaceCandidate candidate) {
        return (candidate.ocrReviewRequired() ? 0 : 4) + candidate.ocrAgreement();
    }

    private String normalizeState(String state) {
        String normalized = state == null ? "" : state.trim().toUpperCase(Locale.ROOT);
        if (!normalized.isEmpty() && !normalized.matches("[A-Z]{2}")) throw new IllegalArgumentException("Use a two-letter default state");
        return normalized;
    }

    private String getUserId(OAuth2User principal) {
        if (principal == null) throw new AccessDeniedException("Sign in first");
        String subject = principal.getName();
        if (subject == null || !subject.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new AccessDeniedException("A TAP account identity is required");
        }
        return subject;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", exception.getMessage()));
    }

    private record ImageWork(String filename, String hash, List<PlaceCandidate> cached, int start, int end) { }

    public record SessionRequest(List<PlaceCandidate> candidates, List<String> imageHashes, String vin, String defaultState) { }
}
