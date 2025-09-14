package com.jtdev.routelisttotesla.controller;


import com.jtdev.routelisttotesla.model.FleetApi;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.PlaceCandidatesResponse;
import com.jtdev.routelisttotesla.service.AddressOcrService;
import com.jtdev.routelisttotesla.service.GeocodingClient;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/route")
@Validated
public class RouteController {
    private final AddressOcrService ocr;
    private final GeocodingClient geocoder;

    @Autowired
    FleetApi fleetApi;

    public RouteController(AddressOcrService ocr, GeocodingClient geocoder) {
        this.ocr = ocr; this.geocoder = geocoder;
    }

    @PostMapping(value = "/places", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PlaceCandidatesResponse places(@RequestPart("images") @NotEmpty MultipartFile[] images) throws Exception {
        // 1) OCR all images
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (MultipartFile f : images) {
            candidates.addAll(ocr.extractAddressCandidates(f.getBytes(), Objects.requireNonNull(f.getOriginalFilename())));
        }

        // 2) Normalize + de-duplicate by normalized text
        LinkedHashMap<String, PlaceCandidate> uniq = new LinkedHashMap<>();
        for (PlaceCandidate c : candidates) uniq.putIfAbsent(c.normalized(), c);

        // 3) Geocode each candidate to place_id
        List<PlaceCandidate> resolved = geocoder.batchGeocode(new ArrayList<>(uniq.values()));

        return new PlaceCandidatesResponse(resolved);
    }

    @PostMapping(value = "/places/{vehicle}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public boolean sendToTesla(@PathVariable String vehicle, @RequestPart("images") @NotEmpty MultipartFile[] images) throws Exception {
        String vin = vehicle.equalsIgnoreCase("ct") ? "7G2CEHEE8SA071432" : "5YJXCBE27GF010886";
        PlaceCandidatesResponse response = places(images);
//        var index = 1;
//        for (PlaceCandidate c : response.candidates()) {
//            Map<String, Object> requestMap = Map.of("lat", c.lat(), "lon", c.lon(), "order", index++);
//          fleetApi.commandNavigationGpsRequest(vin, requestMap);
//            Thread.sleep(5000);
//        }
//        return true;
        return fleetApi.commandNavigationWaypointsRequest(vin, Map.of("waypoints", response.candidates().stream().map(x -> "refId:" + x.pid()).collect(Collectors.joining(",")))).contains("true");

    }
}
