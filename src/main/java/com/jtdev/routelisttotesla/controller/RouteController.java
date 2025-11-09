package com.jtdev.routelisttotesla.controller;


import com.jtdev.routelisttotesla.model.FleetApi;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.PlaceCandidatesResponse;
import com.jtdev.routelisttotesla.service.AddressOcrService;
import com.jtdev.routelisttotesla.service.GeocodingClient;
import com.jtdev.routelisttotesla.service.ImageCacheService;
import jakarta.validation.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    @Autowired
    FleetApi fleetApi;

    public RouteController(AddressOcrService ocr, GeocodingClient geocoder, ImageCacheService cacheService) {
        this.ocr = ocr; 
        this.geocoder = geocoder;
        this.cacheService = cacheService;
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
}
