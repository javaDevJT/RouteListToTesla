package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.StreetSuffixCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class GeocodingClientTest {
    @Test
    void cityMatchFallsBackWithinTheSameBatchAndPreservesDuplicateStops() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        List<Long> starts = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            starts.add(System.nanoTime());
            lookups.add(addressParameter(exchange));
            respond(exchange, addressParameter(exchange).contains("APT") ? partialCityResult()
                    : okAddress("street-address", "12345", "W SAMPLE ST", 42.3, -83.2, false));
        });
        server.start();
        try {
            String full = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
            GeocodingClient client = new GeocodingClient("test-key",
                    "http://localhost:" + server.getAddress().getPort() + "/geocode", "us", 10, 10, 1, 1, 60);
            assertEquals("street-address", client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST", "warmup.png", 0))).getFirst().pid());
            lookups.clear();
            starts.clear();
            List<PlaceCandidate> result = client.batchGeocode("owner-a", List.of(
                    place(full, "a.png", 4), place(full, "b.png", 7)));

            assertEquals(List.of(full, "12345 W SAMPLE STREET APT 212, SAMPLE CITY, MI", full,
                    "12345 W SAMPLE ST, SAMPLE CITY, MI"), lookups);
            assertEquals(List.of(full, full), result.stream().map(PlaceCandidate::text).toList());
            assertEquals(List.of("a.png", "b.png"), result.stream().map(PlaceCandidate::sourceImage).toList());
            assertEquals(List.of(4, 7), result.stream().map(PlaceCandidate::lineIndex).toList());
            assertTrue(result.stream().allMatch(candidate -> "street-address".equals(candidate.pid())
                    && candidate.resolution().status() == LocationResolution.Status.VERIFIED_BUILDING));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0)) >= 50);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingOrRejectedApartmentMatchFallsBackToAnAcceptedStreetAddress() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            lookups.add(addressParameter(exchange));
            respond(exchange, addressParameter(exchange).contains("APT") ? partialCityResult()
                    : okAddress("accepted", "12345", "W SAMPLE ST", 42.3, -83.2, false));
        });
        server.start();
        try {
            List<PlaceCandidate> inputs = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                String full = "12345 W SAMPLE ST APT " + index + ", SAMPLE CITY, MI";
                inputs.add(place(full, "scan.png", index));
                expected.add(full);
                expected.add("12345 W SAMPLE STREET APT " + index + ", SAMPLE CITY, MI");
                expected.add(full);
                expected.add("12345 W SAMPLE ST, SAMPLE CITY, MI");
            }
            List<PlaceCandidate> result = client(server, 30, 30, 1, 1).batchGeocode("owner-a", inputs);
            assertEquals(expected, lookups);
            assertTrue(result.stream().allMatch(candidate -> "accepted".equals(candidate.pid())
                    && candidate.resolution().status() == LocationResolution.Status.VERIFIED_BUILDING));
            assertEquals(inputs.stream().map(PlaceCandidate::text).toList(), result.stream().map(PlaceCandidate::text).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void triesStructuredLocalityBeforeUnitlessFallbackAndPreservesInputRows() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookups.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            String full = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
            String noUnit = "12345 W SAMPLE ST  , SAMPLE CITY, MI";
            List<PlaceCandidate> result = client(server, 10, 10, 1, 1).batchGeocode("owner-a",
                    List.of(place(full, "a.png", 0), place(noUnit, "b.png", 1)));
            assertEquals(List.of(full, "12345 W SAMPLE STREET APT 212, SAMPLE CITY, MI", full,
                    "12345 W SAMPLE ST, SAMPLE CITY, MI", noUnit,
                    "12345 W SAMPLE STREET  , SAMPLE CITY, MI", noUnit), lookups);
            assertTrue(result.stream().allMatch(candidate -> candidate.pid() == null && candidate.lat() == 0 && candidate.lon() == 0));
            assertTrue(result.stream().allMatch(candidate -> candidate.resolution().status() == LocationResolution.Status.NO_MATCH));
            assertEquals(List.of(full, noUnit), result.stream().map(PlaceCandidate::text).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransientProviderFailuresForTheSameApartmentAddressAtMostThreeTimes() throws Exception {
        String full = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            lookups.add(addressParameter(exchange));
            int request = requests.getAndIncrement();
            if (request == 0) {
                respond(exchange, 408, "private provider details");
            } else if (request == 1) {
                respond(exchange, "{\"status\":\"UNKNOWN_ERROR\",\"results\":[]}");
            } else {
                respond(exchange, okUnitAddress("accepted", "12345", "W SAMPLE ST", "212",
                        42.3, -83.2, "SAMPLE CITY", "MI"));
            }
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 3, 3, 1, 1)
                    .batchGeocode("owner-a", List.of(place(full, "scan.png", 0))).getFirst();

            assertEquals(3, requests.get());
            assertEquals(List.of(full, full, full), lookups);
            assertEquals(full, result.text());
            assertEquals("accepted", result.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesTransportFailuresForTheSameRequestAtMostThreeTimes() {
        String address = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<String> success = successfulResponse(okUnitAddress("accepted", "12345", "W SAMPLE ST", "212",
                42.3, -83.2, "SAMPLE CITY", "MI"));
        CompletableFuture<HttpResponse<String>> ioFailure =
                CompletableFuture.failedFuture(new IOException("private transport detail"));
        CompletableFuture<HttpResponse<String>> timeoutFailure =
                CompletableFuture.failedFuture(new HttpTimeoutException("private timeout detail"));
        when(httpClient.<String>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(ioFailure, timeoutFailure, CompletableFuture.completedFuture(success));
        GeocodingClient client = client(httpClient, 3, 3, 1, 1);

        PlaceCandidate result = client.batchGeocode("owner-a", List.of(place(address, "scan.png", 0))).getFirst();
        ArgumentCaptor<HttpRequest> sentRequests = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).<String>sendAsync(sentRequests.capture(), any());

        assertEquals(1L, sentRequests.getAllValues().stream().map(HttpRequest::uri).distinct().count());
        assertEquals("accepted", result.pid());
    }

    @Test
    void exhaustedTransientFailuresStopAfterThreeAttempts() throws Exception {
        String full = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            lookups.add(addressParameter(exchange));
            respond(exchange, 503, "private provider details");
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 3, 3, 1, 1).batchGeocode("owner-a",
                    List.of(place(full, "scan.png", 0))).getFirst();

            assertEquals(3, requests.get());
            assertEquals(List.of(full, full, full), lookups);
            assertEquals(LocationResolution.Status.LOOKUP_UNAVAILABLE, result.resolution().status());
            assertEquals(3, result.resolution().attempts());
            assertNull(result.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retryUsesOwnerBudgetBeforeSendingAndReleasesTheBatchSlot() {
        HttpClient httpClient = mock(HttpClient.class);
        CompletableFuture<HttpResponse<String>> ioFailure =
                CompletableFuture.failedFuture(new IOException("private transport detail"));
        HttpResponse<String> success = successfulResponse(ok("accepted", 42.3, -83.2));
        when(httpClient.<String>sendAsync(any(HttpRequest.class), any()))
                .thenReturn(ioFailure, CompletableFuture.completedFuture(success));
        GeocodingClient client = client(httpClient, 1, 10, 1, 1);

        PlaceCandidate deferred = client.batchGeocode("owner-a",
                List.of(place("12345 W SAMPLE ST", "a.png", 0))).getFirst();

        assertEquals(LocationResolution.Status.DEFERRED, deferred.resolution().status());
        assertEquals("request_budget_exhausted", deferred.resolution().reasonCode());
        verify(httpClient, times(1)).<String>sendAsync(any(HttpRequest.class), any());
        PlaceCandidate accepted = client.batchGeocode("owner-b",
                List.of(place("12345 W SAMPLE ST", "b.png", 0))).getFirst();
        assertEquals("accepted", accepted.pid());
        assertTrue(accepted.resolution().verified());
        verify(httpClient, times(2)).<String>sendAsync(any(HttpRequest.class), any());
    }

    @Test
    void providerFailuresDoNotRetryOrTriggerAddressFallbacks() throws Exception {
        List<Integer> httpStatuses = List.of(200, 200, 200, 200, 200, 200, 429, 501);
        List<String> failures = List.of(
                "{\"status\":\"REQUEST_DENIED\",\"results\":[]}",
                "{\"status\":\"OVER_QUERY_LIMIT\",\"results\":[]}",
                "{\"status\":\"OVER_DAILY_LIMIT\",\"results\":[]}",
                "{\"status\":\"INVALID_REQUEST\",\"results\":[]}",
                "{\"status\":\"OK\"}", "invalid-json",
                "{\"status\":\"OVER_QUERY_LIMIT\",\"results\":[]}", "service-unavailable");
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int request = requests.getAndIncrement();
            respond(exchange, httpStatuses.get(request), failures.get(request));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 20, 20, 1, 1);
            for (int index = 0; index < failures.size(); index++) {
                PlaceCandidate input = place("12345 W SAMPLE ST APT " + index, "scan.png", index);
                PlaceCandidate result = client.batchGeocode("owner-a", List.of(input)).getFirst();
                LocationResolution.Status expected = index == 1 || index == 2 || index == 6
                        ? LocationResolution.Status.DEFERRED : LocationResolution.Status.LOOKUP_UNAVAILABLE;
                assertEquals(expected, result.resolution().status());
                assertEquals(index + 1, requests.get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallbackHonorsOwnerAndGlobalCallBudgetsAndReleasesTheBatchSlot() throws Exception {
        for (int[] limits : List.of(new int[]{1, 5}, new int[]{5, 1})) {
            AtomicInteger requests = new AtomicInteger();
            HttpServer server = server(exchange -> {
                requests.incrementAndGet();
                respond(exchange, addressParameter(exchange).contains("APT") ? partialCityResult()
                        : okAddress("street-address", "12345", "W SAMPLE ST", 42.3, -83.2, false));
            });
            server.start();
            try {
                GeocodingClient client = client(server, limits[0], limits[1], 1, 1);
                PlaceCandidate deferred = client.batchGeocode("owner-a",
                        List.of(place("12345 W SAMPLE ST APT 212, SAMPLE CITY, MI", "scan.png", 0))).getFirst();
                assertEquals(LocationResolution.Status.DEFERRED, deferred.resolution().status());
                assertEquals(1, requests.get());
                if (limits[1] > 1) {
                    PlaceCandidate secondOwner = client.batchGeocode("owner-b",
                            List.of(place("12345 W SAMPLE ST", "scan.png", 0))).getFirst();
                    assertEquals("street-address", secondOwner.pid());
                    assertEquals(2, requests.get());
                } else {
                    PlaceCandidate secondOwner = client.batchGeocode("owner-b",
                            List.of(place("12345 W SAMPLE ST", "scan.png", 0))).getFirst();
                    assertEquals(LocationResolution.Status.DEFERRED, secondOwner.resolution().status());
                    assertEquals(1, requests.get());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void usesFullApartmentAddressWhenItMatchesAndPreservesOriginalCandidate() throws Exception {
        List<String> lookupAddresses = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookupAddresses.add(addressParameter(exchange));
            respond(exchange, okUnitAddress("apartment-address", "12345", "W SAMPLE ST", "212",
                    42.3, -83.2, "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 1, 1, 1, 1);
            String originalText = "12345 W SAMPLE ST APT 212, JAMESTOWN VILLAGE, SAMPLE CITY, MI";
            PlaceCandidate original = new PlaceCandidate(originalText, "OCR NORMALIZED WITH APT 212", "scan.png",
                    4, 0, 0, null, 2, true, List.of("12345 W SAMPLE ST"));

            PlaceCandidate result = client.batchGeocode("owner-a", List.of(original)).get(0);

            assertEquals(List.of(originalText), lookupAddresses);
            assertEquals(originalText, result.text());
            assertEquals(original.normalized(), result.normalized());
            assertEquals(original.sourceImage(), result.sourceImage());
            assertEquals(original.lineIndex(), result.lineIndex());
            assertEquals(original.ocrAgreement(), result.ocrAgreement());
            assertEquals(original.ocrReviewRequired(), result.ocrReviewRequired());
            assertEquals(original.ocrAlternatives(), result.ocrAlternatives());
            assertEquals("apartment-address", result.pid());
            assertEquals(42.3, result.lat());
            assertEquals(-83.2, result.lon());
            assertEquals(LocationResolution.Status.VERIFIED_UNIT, result.resolution().status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ignoresInboundCoordinatesPlaceIdAndCurrentVerifiedMetadata() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            // A city-center result has plausible coordinates and a place ID, but no street-address type.
            respond(exchange, partialCityResult());
        });
        server.start();
        try {
            String text = "12345 W SAMPLE ST, SAMPLE CITY, MI";
            LocationResolution oldVerification = new LocationResolution(LocationResolution.Status.VERIFIED_BUILDING,
                    "original", "exact_street_match", 1, GeocodingClient.GEOCODING_VERSION, null);
            PlaceCandidate inbound = new PlaceCandidate(text, "OCR TEXT", "scan.png", 7,
                    42.3, -83.2, "stale-valid-place", 2, true, List.of("OCR ALTERNATIVE"), oldVerification);

            PlaceCandidate result = client(server, 10, 10, 1, 1)
                    .batchGeocode("owner-a", List.of(inbound)).getFirst();

            assertEquals(3, requests.get());
            assertEquals(text, result.text());
            assertEquals(inbound.normalized(), result.normalized());
            assertEquals(inbound.sourceImage(), result.sourceImage());
            assertEquals(inbound.ocrAgreement(), result.ocrAgreement());
            assertTrue(result.resolution() != null);
            assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            assertEquals("no_safe_match", result.resolution().reasonCode());
            assertEquals(GeocodingClient.GEOCODING_VERSION, result.resolution().policyVersion());
            assertNull(result.pid());
            assertEquals(0, result.lat());
            assertEquals(0, result.lon());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void removesAnExplicitComplexOnlyAfterTheOriginalApartmentQueryMisses() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            lookups.add(query);
            respond(exchange, query.contains("JAMESTOWN VILLAGE")
                    ? "{\"status\":\"ZERO_RESULTS\",\"results\":[]}"
                    : okAddress("fordline", "11570", "FORDLINE STREET", 42.25, -83.2, false,
                            "ALLEN PARK", "MI"));
        });
        server.start();
        try {
            String originalText = "11570 FORDLINE ST APT201, JAMESTOWN VILLAGE, ALLEN PARK, MI";
            PlaceCandidate result = client(server, 10, 10, 1, 1)
                    .batchGeocode("owner-a", List.of(place(originalText, "scan.png", 0))).getFirst();

            assertEquals(List.of(originalText, "11570 FORDLINE ST APT201, ALLEN PARK, MI",
                    "11570 FORDLINE ST APT 201, ALLEN PARK, MI",
                    "11570 FORDLINE STREET APT 201, ALLEN PARK, MI",
                    "11570 FORDLINE ST APT201, ALLEN PARK, MI"), lookups);
            assertEquals(originalText, result.text());
            assertEquals("fordline", result.pid());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsApartmentAcrossTheUnitPreservingTierAndSkipsFallbackAfterBuildingMatch() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            lookups.add(query);
            respond(exchange, query.contains("APT201")
                    ? "{\"status\":\"ZERO_RESULTS\",\"results\":[]}"
                    : okAddress("fordline", "11570", "FORDLINE ST", 42.25, -83.2, false,
                            "ALLEN PARK", "MI"));
        });
        server.start();
        try {
            String originalText = "11570 FORDLINE ST APT201, JAMESTOWN VILLAGE, ALLEN PARK, MI";
            PlaceCandidate result = client(server, 10, 10, 1, 1)
                    .batchGeocode("owner-a", List.of(place(originalText, "scan.png", 0))).getFirst();

            assertEquals(List.of(originalText, "11570 FORDLINE ST APT201, ALLEN PARK, MI",
                    "11570 FORDLINE ST APT 201, ALLEN PARK, MI",
                    "11570 FORDLINE STREET APT 201, ALLEN PARK, MI",
                    "11570 FORDLINE ST APT201, ALLEN PARK, MI"), lookups);
            assertEquals(originalText, result.text());
            assertEquals("fordline", result.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void removesMarkedComplexWithoutAnApartmentUnitWhenSeparateFinalCityRemains() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            lookups.add(query);
            respond(exchange, query.contains("JAMESTOWN VILLAGE")
                    ? "{\"status\":\"ZERO_RESULTS\",\"results\":[]}"
                    : okAddress("fordline", "11570", "FORDLINE ST", 42.25, -83.2, false,
                            "ALLEN PARK", "MI"));
        });
        server.start();
        try {
            String originalText = "11570 FORDLINE ST, JAMESTOWN VILLAGE, ALLEN PARK, MI";
            PlaceCandidate result = client(server, 2, 2, 1, 1)
                    .batchGeocode("owner-a", List.of(place(originalText, "scan.png", 0))).getFirst();

            assertEquals(List.of(originalText, "11570 FORDLINE ST, ALLEN PARK, MI"), lookups);
            assertEquals(originalText, result.text());
            assertEquals("fordline", result.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsUnmarkedOrOnlyLocalityHintsWhenNoStreetMatchExists() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookups.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            String unmarked = "11570 FORDLINE ST, HAMTRAMCK, ALLEN PARK, MI";
            String onlyLocality = "11570 FORDLINE ST, JAMESTOWN VILLAGE, MI";
            List<PlaceCandidate> results = client(server, 20, 20, 1, 1).batchGeocode("owner-a", List.of(
                    place(unmarked, "scan.png", 0), place(onlyLocality, "scan.png", 1)));

            assertEquals(List.of(unmarked, "11570 FORDLINE STREET, HAMTRAMCK, ALLEN PARK, MI", unmarked,
                    onlyLocality, "11570 FORDLINE STREET, JAMESTOWN VILLAGE, MI", onlyLocality), lookups);
            assertEquals(List.of(unmarked, onlyLocality), results.stream().map(PlaceCandidate::text).toList());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
            assertTrue(results.stream().allMatch(result -> result.resolution().status() == LocationResolution.Status.NO_MATCH));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMatchingHouseNumberWhenStreetCityOrStateDiffers() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            String address = addressParameter(exchange);
            String response = address.contains("OTHER CITY")
                    ? okAddress("wrong-city", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                            "SAMPLE CITY", "MI")
                    : address.endsWith(", OH")
                            ? okAddress("wrong-state", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                                    "SAMPLE CITY", "MI")
                            : okAddress("wrong-street", "12345", "W OTHER ST", 42.3, -83.2, false,
                                    "SAMPLE CITY", "MI");
            respond(exchange, response);
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 0),
                    place("12345 W SAMPLE ST, OTHER CITY, MI", "scan.png", 1),
                    place("12345 W SAMPLE ST, SAMPLE CITY, OH", "scan.png", 2)));

            assertEquals(9, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
            assertTrue(results.stream().allMatch(result -> result.resolution().status() == LocationResolution.Status.NO_MATCH));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesFlatCityAndStateHintsIncludingAUnit() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String address = addressParameter(exchange);
            lookups.add(address);
            String houseNumber = address.startsWith("54321") ? "54321" : "12345";
            respond(exchange, okAddress(address.startsWith("54321") ? "flat-unit" : "flat-city",
                    houseNumber, "W SAMPLE ST", 42.3, -83.2, false, "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            String withoutUnit = "12345 W SAMPLE ST SAMPLE CITY MI";
            String withUnit = "54321 W SAMPLE ST APT212 SAMPLE CITY MI";
            List<PlaceCandidate> results = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place(withoutUnit, "flat.png", 0), place(withUnit, "flat-unit.png", 1)));

            assertEquals(List.of(withoutUnit, withUnit, "54321 W SAMPLE ST APT 212, SAMPLE CITY MI",
                    "54321 W SAMPLE STREET APT 212, SAMPLE CITY MI", withUnit), lookups);
            assertEquals(List.of("flat-city", "flat-unit"), results.stream().map(PlaceCandidate::pid).toList());
            assertTrue(results.stream().allMatch(result -> result.resolution().status()
                    == LocationResolution.Status.VERIFIED_BUILDING));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void flatCityAndStateHintsStillRejectWrongLocalities() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int request = requests.getAndIncrement();
            respond(exchange, request == 0
                    ? okAddress("wrong-city", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                    "OTHER CITY", "MI")
                    : okAddress("wrong-state", "54321", "W SAMPLE ST", 42.3, -83.2, false,
                    "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST SAMPLE CITY MI", "flat.png", 0),
                    place("54321 W SAMPLE ST SAMPLE CITY OH", "flat.png", 1)));

            assertEquals(6, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
            assertTrue(results.stream().allMatch(result -> result.resolution().status() == LocationResolution.Status.NO_MATCH));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesFullStateNamesFromCommaSeparatedAddresses() throws Exception {
        String address = "12345 W SAMPLE ST, SAMPLE CITY, Michigan";
        HttpServer server = server(exchange -> respond(exchange,
                okAddress("full-state", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                        "SAMPLE CITY", "MI")));
        server.start();
        try {
            PlaceCandidate result = client(server, 3, 3, 1, 1)
                    .batchGeocode("owner-a", List.of(place(address, "scan.png", 0))).getFirst();

            assertEquals("full-state", result.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void normalizesLogicalDirectionalStreetSuffixesInFlatAddresses() throws Exception {
        List<String> addresses = List.of(
                "12345 MAIN ST N",
                "54321 W MAIN ST",
                "67890 MEADOWS BOULEVARD CITY MI",
                "78901 NORTH EAST SAMPLE PT SAMPLE CITY MI");
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String address = addressParameter(exchange);
            lookups.add(address);
            if (address.startsWith("12345")) {
                respond(exchange, okAddress("directional-suffix", "12345", "MAIN STREET NORTH",
                        42.3, -83.2, false));
            } else if (address.startsWith("54321")) {
                respond(exchange, okAddress("directional-prefix", "54321", "WEST MAIN STREET",
                        42.3, -83.2, false));
            } else if (address.startsWith("67890")) {
                respond(exchange, okAddress("city-named-city", "67890", "MEADOWS BOULEVARD",
                        42.3, -83.2, false, "CITY", "MI"));
            } else {
                respond(exchange, okAddress("flat-diagonal", "78901", "NORTHEAST SAMPLE POINT",
                        42.3, -83.2, false, "SAMPLE CITY", "MI"));
            }
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 5, 5, 1, 1).batchGeocode("owner-a", addresses.stream()
                    .map(address -> place(address, "flat.png", 0)).toList());

            assertEquals(addresses, lookups);
            assertEquals(List.of("directional-suffix", "directional-prefix", "city-named-city", "flat-diagonal"),
                    results.stream().map(PlaceCandidate::pid).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerErrorsDoNotTriggerComplexAddressPermutations() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            lookups.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"OVER_QUERY_LIMIT\",\"results\":[]}");
        });
        server.start();
        try {
            String originalText = "11570 FORDLINE ST APT201, JAMESTOWN VILLAGE, ALLEN PARK, MI";
            PlaceCandidate result = client(server, 3, 3, 1, 1)
                    .batchGeocode("owner-a", List.of(place(originalText, "scan.png", 0))).getFirst();

            assertEquals(1, requests.get());
            assertEquals(List.of(originalText), lookups);
            assertEquals(LocationResolution.Status.DEFERRED, result.resolution().status());
            assertEquals("provider_quota", result.resolution().reasonCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void formattingVariantsPreserveTheUnitDesignatorInsteadOfInventingAnApartment() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            queries.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            List<String> originals = List.of("123 MAIN ST STE.2, SAMPLE CITY, MI",
                    "456 MAIN ST ROOM 3, SAMPLE CITY, MI", "789 MAIN ST FLOOR 4, SAMPLE CITY, MI");
            client(server, 40, 40, 1, 1).batchGeocode("owner-a",
                    originals.stream().map(text -> place(text, "scan.png", 0)).toList());
            assertTrue(queries.contains("123 MAIN ST STE 2, SAMPLE CITY, MI"));
            assertFalse(queries.stream().anyMatch(query -> query.contains("APT")));
        } finally { server.stop(0); }
    }

    @Test
    void stripsKnownUnitFormsWithoutTreatingRoadNamesOrHouseNumbersAsUnits() throws Exception {
        List<String> lookupAddresses = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookupAddresses.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            List<String> originals = List.of(
                    "123 MAIN ST APARTMENT 3B, SAMPLE CITY, MI",
                    "80 MAIN ST STE 4B, SAMPLE CITY, MI",
                    "81 MAIN ST SUITE 5, SAMPLE CITY, MI",
                    "22 MAIN ST UNIT 6, SAMPLE CITY, MI",
                    "55 MAIN ST # 212, SAMPLE CITY, MI",
                    "56 MAIN ST, APT 212, SAMPLE CITY, MI",
                    "57 MAIN ST APT212, SAMPLE CITY, MI",
                    "58 MAIN ST STE.2, SAMPLE CITY, MI",
                    "33 UNIT ROAD, SAMPLE CITY, MI",
                    "59 UNIT 5 ROAD, SAMPLE CITY, MI",
                    "44 APARTMENT LANE, SAMPLE CITY, MI",
                    "12 1/2 MAIN ST APT 5, SAMPLE CITY, MI",
                    "12.5 MAIN ST APT 5, SAMPLE CITY, MI",
                    "12A MAIN ST APT 5, SAMPLE CITY, MI");
            List<String> expectedLookups = List.of(
                    "123 MAIN ST, SAMPLE CITY, MI",
                    "80 MAIN ST, SAMPLE CITY, MI",
                    "81 MAIN ST, SAMPLE CITY, MI",
                    "22 MAIN ST, SAMPLE CITY, MI",
                    "55 MAIN ST, SAMPLE CITY, MI",
                    "56 MAIN ST, SAMPLE CITY, MI",
                    "57 MAIN ST, SAMPLE CITY, MI",
                    "58 MAIN ST, SAMPLE CITY, MI",
                    "33 UNIT ROAD, SAMPLE CITY, MI",
                    "59 UNIT 5 ROAD, SAMPLE CITY, MI",
                    "44 APARTMENT LANE, SAMPLE CITY, MI",
                    "12 1/2 MAIN ST, SAMPLE CITY, MI",
                    "12.5 MAIN ST, SAMPLE CITY, MI",
                    "12A MAIN ST, SAMPLE CITY, MI");

            List<PlaceCandidate> results = client(server, 60, 60, 2, 4).batchGeocode("owner-a",
                    originals.stream().map(text -> place(text, "scan.png", 0)).toList());

            List<String> rowPrefixes = List.of("123 MAIN ST", "80 MAIN ST", "81 MAIN ST", "22 MAIN ST",
                    "55 MAIN ST", "56 MAIN ST", "57 MAIN ST", "58 MAIN ST", "33 UNIT ROAD",
                    "59 UNIT 5 ROAD", "44 APARTMENT LANE", "12 1/2 MAIN ST", "12.5 MAIN ST", "12A MAIN ST");
            for (int index = 0; index < originals.size(); index++) {
                String prefix = rowPrefixes.get(index);
                List<String> rowQueries = lookupAddresses.stream().filter(query -> query.startsWith(prefix)).toList();
                assertFalse(rowQueries.isEmpty());
                assertEquals(originals.get(index), rowQueries.getFirst());
                if (originals.get(index).equals(expectedLookups.get(index))) {
                    assertTrue(rowQueries.stream().allMatch(originals.get(index)::equals));
                } else {
                    assertEquals(expectedLookups.get(index), rowQueries.getLast());
                    assertTrue(rowQueries.subList(0, rowQueries.size() - 1).stream()
                            .allMatch(query -> query.matches("(?i).*(?:\\b(?:APT|APARTMENT|UNIT|STE|SUITE|BLDG|BUILDING|FLOOR|ROOM|RM|DEPT)(?=\\b|\\d)|#).*")));
                }
            }
            assertEquals(originals, results.stream().map(PlaceCandidate::text).toList());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsBroadPartialAndNonMatchingStreetResults() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int request = requests.incrementAndGet();
            String response = switch (request) {
                case 1 -> partialCityResult();
                case 2 -> okAddress("partial-street", "12345", "W SAMPLE ST", 42.3, -83.2, true);
                case 3 -> okAddress("missing-number", null, "W SAMPLE ST", 42.3, -83.2, false);
                default -> okAddress("wrong-number", "12346", "W SAMPLE ST", 42.3, -83.2, false);
            };
            respond(exchange, response);
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 20, 20, 2, 4).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 0),
                    place("12345 W SAMPLE ST", "scan.png", 1),
                    place("12345 W SAMPLE ST, SECOND CITY, MI", "scan.png", 2),
                    place("12345 W SAMPLE ST, THIRD CITY, MI", "scan.png", 3)));

            assertEquals(11, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
            assertTrue(results.stream().allMatch(result -> result.resolution().status() == LocationResolution.Status.NO_MATCH));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsExactStreetAddressWithMatchingHouseNumber() throws Exception {
        HttpServer server = server(exchange -> respond(exchange,
                okAddress("exact-street", "12345", "W SAMPLE ST", 42.3, -83.2, false)));
        server.start();
        try {
            PlaceCandidate result = client(server, 10, 20, 2, 4)
                    .batchGeocode("owner-a", List.of(place("12345 W SAMPLE ST", "scan.png", 0))).get(0);

            assertEquals("exact-street", result.pid());
            assertEquals(42.3, result.lat());
            assertEquals(-83.2, result.lon());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsSanitizedProviderShapesForSaintAndTrailRouteAliases() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            queries.add(query);
            String response;
            if (query.startsWith("123")) {
                response = okRouteAliases("saint-clair", "123", "Saint Clair Court", "St Clair Ct",
                        42.3, -83.2, "TESTVILLE", "MI");
                response = response.replace("\"types\":[\"street_address\"]",
                                "\"types\":[\"street_address\",\"subpremise\"]")
                        .replace("],\"geometry\"", ",{\"long_name\":\"104\",\"short_name\":\"104\","
                                + "\"types\":[\"subpremise\"]}],\"geometry\"");
            } else {
                response = okRouteAliases("knotty-pine", "456", "Knotty Pine Tr", "Knotty Pine Tr",
                        42.31, -83.21, "TESTVILLE", "MI");
            }
            response = response.replace("],\"geometry\"", ",{\"long_name\":\"Different Township\","
                    + "\"short_name\":\"Different Township\",\"types\":[\"administrative_area_level_3\",\"political\"]}],\"geometry\"");
            respond(exchange, response);
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("123 ST CLAIR CT APT 104, TESTVILLE, MI", "a.png", 0),
                    place("456 KNOTTY PINE TRL, TESTVILLE, MI", "b.png", 1)));

            assertEquals(List.of("saint-clair", "knotty-pine"), results.stream().map(PlaceCandidate::pid).toList());
            assertEquals(LocationResolution.Status.VERIFIED_UNIT, results.get(0).resolution().status());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, results.get(1).resolution().status());
            assertEquals(List.of("123 ST CLAIR CT APT 104, TESTVILLE, MI",
                    "456 KNOTTY PINE TRL, TESTVILLE, MI"), queries);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsPointAndAnUnincorporatedTownshipCommunityInOneOriginalQueryEach() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            queries.add(query);
            String response = query.startsWith("123")
                    ? okRouteAliases("point", "123", "Doves Point", "Doves Point", 42.3, -83.2, "TESTVILLE", "MI")
                    : townshipAddress("countryside", "TESTHILL", "TESTHILL Township");
            respond(exchange, response);
        });
        server.start();
        try {
            List<String> originals = List.of("123 DOVES PT, TESTVILLE, MI", "456 COUNTRYSIDE LN, TESTHILL, MI");
            List<PlaceCandidate> results = client(server, 20, 20, 1, 1).batchGeocode("owner-a", List.of(
                    place(originals.get(0), "first.heic", 0), place(originals.get(1), "first.heic", 3)));
            assertEquals(List.of("point", "countryside"), results.stream().map(PlaceCandidate::pid).toList());
            assertEquals(originals, queries);
            assertEquals(originals, results.stream().map(PlaceCandidate::text).toList());
            assertTrue(results.stream().allMatch(row -> row.resolution().attempts() == 1
                    && row.resolution().status() == LocationResolution.Status.VERIFIED_BUILDING));
        } finally { server.stop(0); }
    }

    @Test
    void everyCatalogAliasCanBeComparedAtSuffixPositionWithoutChangingStreetNames() {
        for (String alias : StreetSuffixCatalog.aliases()) {
            String primary = StreetSuffixCatalog.canonical(alias);
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(GeocodingClient.class,
                    "streetRoutesMatch", "SAMPLE " + alias, "SAMPLE " + primary)), alias);
        }
        for (String[] pair : List.of(new String[]{"SAMPLE PT", "SAMPLE POINTS"},
                new String[]{"SAMPLE PARKWAY", "SAMPLE PARKWAYS"},
                new String[]{"SAMPLE WALK", "SAMPLE WALKS"},
                new String[]{"PT SAMPLE RD", "POINT SAMPLE ROAD"},
                new String[]{"COUNTRYSIDE LN", "COUNTRY SIDE LANE"},
                new String[]{"N SAMPLE PT", "S SAMPLE POINT"},
                new String[]{"PINE NORTHWEST RD", "PINE NW ROAD"},
                new String[]{"PINE NORTH RD", "PINE N ROAD"},
                new String[]{"NORTH EAST SAMPLE PT", "NORTHWEST SAMPLE POINT"},
                new String[]{"N E SAMPLE PT", "SOUTHEAST SAMPLE POINT"},
                new String[]{"SAMPLE PT NORTH EAST", "SAMPLE POINT SOUTH EAST"},
                new String[]{"SAMPLE PT N E", "SAMPLE POINT NORTHWEST"},
                new String[]{"SAMPLE NORTH EAST PT", "SAMPLE NORTHEAST POINT"})) {
            assertFalse(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(GeocodingClient.class,
                    "streetRoutesMatch", pair[0], pair[1])), List.of(pair).toString());
        }
        for (String[] pair : List.of(new String[]{"SAMPLE PKWY", "SAMPLE PARKWAY"},
                new String[]{"SAMPLE PKWY", "SAMPLE PARKWAYS"},
                new String[]{"MT PLEASANT RD", "MOUNT PLEASANT ROAD"},
                new String[]{"FT SAMPLE BLVD", "FORT SAMPLE BOULEVARD"},
                new String[]{"NORTH ST CLAIR CT", "NORTH SAINT CLAIR COURT"},
                new String[]{"NORTH MT PLEASANT RD", "NORTH MOUNT PLEASANT ROAD"},
                new String[]{"NORTH FT SAMPLE BLVD", "NORTH FORT SAMPLE BOULEVARD"},
                new String[]{"N SAMPLE PT", "NORTH SAMPLE POINT"},
                new String[]{"S SAMPLE PT", "SOUTH SAMPLE POINT"},
                new String[]{"E SAMPLE PT", "EAST SAMPLE POINT"},
                new String[]{"W SAMPLE PT", "WEST SAMPLE POINT"},
                new String[]{"NE SAMPLE PT", "NORTHEAST SAMPLE POINT"},
                new String[]{"NW SAMPLE PT", "NORTHWEST SAMPLE POINT"},
                new String[]{"SE SAMPLE PT", "SOUTHEAST SAMPLE POINT"},
                new String[]{"SW SAMPLE PT", "SOUTHWEST SAMPLE POINT"},
                new String[]{"N E SAMPLE PT", "NORTHEAST SAMPLE POINT"},
                new String[]{"NORTH EAST SAMPLE PT", "NORTHEAST SAMPLE POINT"},
                new String[]{"SAMPLE PT N", "SAMPLE POINT NORTH"},
                new String[]{"SAMPLE PT S", "SAMPLE POINT SOUTH"},
                new String[]{"SAMPLE PT E", "SAMPLE POINT EAST"},
                new String[]{"SAMPLE PT W", "SAMPLE POINT WEST"},
                new String[]{"SAMPLE PT NE", "SAMPLE POINT NORTHEAST"},
                new String[]{"SAMPLE PT NW", "SAMPLE POINT NORTHWEST"},
                new String[]{"SAMPLE PT SE", "SAMPLE POINT SOUTHEAST"},
                new String[]{"SAMPLE PT SW", "SAMPLE POINT SOUTHWEST"},
                new String[]{"SAMPLE PT N E", "SAMPLE POINT NORTHEAST"},
                new String[]{"SAMPLE PT NORTH EAST", "SAMPLE POINT NORTHEAST"})) {
            assertTrue(Boolean.TRUE.equals(ReflectionTestUtils.invokeMethod(GeocodingClient.class,
                    "streetRoutesMatch", pair[0], pair[1])), List.of(pair).toString());
        }
    }

    @Test
    void aSharedShortSuffixCannotOverrideAnIncompatibleFullSuffix() throws Exception {
        for (String[] suffixes : List.of(new String[]{"PARK", "PARKS", "PARK"},
                new String[]{"PARKWAY", "PARKWAYS", "PKWY"},
                new String[]{"PARKWAYS", "PARKWAY", "PKWY"},
                new String[]{"MEADOW", "MEADOWS", "MDW"})) {
            HttpServer server = server(exchange -> respond(exchange,
                    okRouteAliases("other-street", "123", "SAMPLE " + suffixes[1], "SAMPLE " + suffixes[2],
                            42.3, -83.2, "TESTVILLE", "MI")));
            server.start();
            try {
                PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("123 SAMPLE " + suffixes[0] + ", TESTVILLE, MI", "scan.png", 0))).getFirst();
                assertNull(result.pid(), String.join("/", suffixes));
                assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            } finally { server.stop(0); }
        }
    }

    @Test
    void triesAnOfficialFullSuffixBeforeStructuredLookupAndRetainsApartment() throws Exception {
        List<String> queries = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String query = addressParameter(exchange);
            queries.add(query);
            respond(exchange, query.contains(" POINT ")
                    ? okUnitAddress("point-unit", "123", "Doves Point", "104", 42.3, -83.2, "TESTVILLE", "MI")
                    : "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            String original = "123 DOVES PT APT104, TESTVILLE, MI";
            PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                    List.of(place(original, "scan.png", 0))).getFirst();
            assertEquals("point-unit", result.pid());
            assertEquals(original, result.text());
            assertEquals(LocationResolution.Status.VERIFIED_UNIT, result.resolution().status());
            assertEquals(List.of(original, "123 DOVES PT APT 104, TESTVILLE, MI",
                    "123 DOVES POINT APT 104, TESTVILLE, MI"), queries);
        } finally { server.stop(0); }
    }

    @Test
    void reservesStructuredAndUnitlessFormsWithinTheSixQueryLimit() throws Exception {
        String original = "12345 PINE TR APT104, JAMESTOWN VILLAGE, SAMPLE CITY, MI";
        String cleaned = "12345 PINE TR APT104, SAMPLE CITY, MI";
        String normalizedUnit = "12345 PINE TR APT 104, SAMPLE CITY, MI";
        String fullSuffix = "12345 PINE TRAIL APT 104, SAMPLE CITY, MI";
        String unitless = "12345 PINE TR, SAMPLE CITY, MI";
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        List<Boolean> structured = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            String address = addressParameter(exchange);
            lookups.add(address);
            structured.add(exchange.getRequestURI().getRawQuery().contains("&components="));
            respond(exchange, address.equals(unitless)
                    ? okAddress("unitless-fallback", "12345", "PINE TRAIL", 42.3, -83.2,
                            false, "SAMPLE CITY", "MI")
                    : "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                    List.of(place(original, "scan.png", 0))).getFirst();

            assertEquals(List.of(original, cleaned, normalizedUnit, fullSuffix, cleaned, unitless), lookups);
            assertEquals(List.of(false, false, false, false, true, false), structured);
            assertEquals("unitless-fallback", result.pid());
            assertEquals(original, result.text());
            assertEquals(6, result.resolution().attempts());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void townshipFallbackRequiresMatchingCommunityAndRejectsConflictingStrongLocalities() throws Exception {
        List<String> invalidResponses = List.of(
                townshipAddress("wrong-neighborhood", "OTHER", "TESTHILL Township"),
                townshipAddress("wrong-township", "TESTHILL", "OTHER Township"),
                townshipAddress("no-township", "TESTHILL", "TESTHILL County"),
                townshipAddress("wrong-state", "TESTHILL", "TESTHILL Township")
                        .replace("\"MI\"", "\"OH\""),
                townshipAddress("city-conflict", "TESTHILL", "TESTHILL Township")
                        .replace("],\"geometry\"", ",{\"long_name\":\"OTHER CITY\",\"short_name\":\"OTHER CITY\","
                                + "\"types\":[\"locality\"]}],\"geometry\""),
                townshipAddress("city-conflict-short-base", "TESTHILL", "TESTHILL Township")
                        .replace("\"short_name\":\"TESTHILL Township\"", "\"short_name\":\"TESTHILL\"")
                        .replace("],\"geometry\"", ",{\"long_name\":\"OTHER CITY\",\"short_name\":\"OTHER CITY\","
                                + "\"types\":[\"locality\"]}],\"geometry\""),
                townshipAddress("missing-country", "TESTHILL", "TESTHILL Township")
                        .replace("\"country\",\"political\"", "\"unrelated\""),
                townshipAddress("city-center", "TESTHILL", "TESTHILL Township")
                        .replace("\"types\":[\"street_address\"]", "\"types\":[\"locality\"]"));
        for (String response : invalidResponses) {
            HttpServer server = server(exchange -> respond(exchange, response));
            server.start();
            try {
                PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("456 COUNTRYSIDE LN, TESTHILL, MI", "scan.png", 0))).getFirst();
                assertNull(result.pid(), response);
                assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            } finally { server.stop(0); }
        }
        for (String township : List.of("TESTHILL Township", "TESTHILL Twp.", "TESTHILL Charter Township",
                "Township of TESTHILL", "Charter Twp of TESTHILL")) {
            HttpServer server = server(exchange -> respond(exchange, townshipAddress("valid", "TESTHILL", township)));
            server.start();
            try {
                assertEquals("valid", client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("456 COUNTRYSIDE LN, TESTHILL, MI", "scan.png", 0))).getFirst().pid());
            } finally { server.stop(0); }
        }
    }

    @Test
    void acceptsCorroboratedTownshipWithoutNeighborhoodAndRejectsUnsafeResults() throws Exception {
        String noNeighborhood = townshipAddress("township-only", "TESTHILL", "TESTHILL Township")
                .replace("{\"long_name\":\"TESTHILL\",\"short_name\":\"TESTHILL\","
                        + "\"types\":[\"neighborhood\",\"political\"]},", "");
        for (String[] sample : List.of(new String[]{"TESTHILL", "township-only"},
                new String[]{"TESTHILL Township", "township-only"},
                new String[]{"", null}, new String[]{"OTHER", null})) {
            String response = noNeighborhood.replace("\"short_name\":\"TESTHILL Township\"",
                    "\"short_name\":\"" + sample[0] + "\"");
            HttpServer server = server(exchange -> respond(exchange, response));
            server.start();
            try {
                PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("456 COUNTRYSIDE LN, TESTHILL, MI", "scan.png", 0))).getFirst();
                assertEquals(sample[1], result.pid(), sample[0]);
                assertEquals(sample[1] == null ? LocationResolution.Status.NO_MATCH
                        : LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status(), sample[0]);
            } finally { server.stop(0); }
        }
        for (String response : List.of(
                noNeighborhood.replace("TESTHILL Township", "OTHER Township"),
                noNeighborhood.replace("\"MI\"", "\"OH\""),
                noNeighborhood.replace("\"US\"", "\"CA\""),
                noNeighborhood.replace("\"partial_match\":false", "\"partial_match\":true"),
                noNeighborhood.replace("\"types\":[\"street_address\"]", "\"types\":[\"locality\"]"),
                noNeighborhood.replace("],\"geometry\"", ",{\"long_name\":\"OTHER CITY\","
                        + "\"short_name\":\"OTHER CITY\",\"types\":[\"locality\"]}],\"geometry\""))) {
            HttpServer server = server(exchange -> respond(exchange, response));
            server.start();
            try {
                PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("456 COUNTRYSIDE LN, TESTHILL, MI", "scan.png", 0))).getFirst();
                assertNull(result.pid(), response);
                assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            } finally { server.stop(0); }
        }
    }

    @Test
    void acceptsOnlyMatchingTownshipAdminAreaShortNameBase() throws Exception {
        for (String[] sample : List.of(new String[]{"TESTHILL", "short-base"},
                new String[]{"OTHER", null})) {
            String response = townshipAddress("short-base", "TESTHILL", "TESTHILL Township")
                    .replace("\"short_name\":\"TESTHILL Township\"", "\"short_name\":\"" + sample[0] + "\"");
            HttpServer server = server(exchange -> respond(exchange, response));
            server.start();
            try {
                PlaceCandidate result = client(server, 20, 20, 1, 1).batchGeocode("owner-a",
                        List.of(place("456 COUNTRYSIDE LN, TESTHILL, MI", "scan.png", 0))).getFirst();
                assertEquals(sample[1], result.pid(), sample[0]);
                assertEquals(sample[1] == null ? LocationResolution.Status.NO_MATCH
                        : LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status(), sample[0]);
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void rejectsContradictoryLocalitiesAtTheSameSemanticLevel() throws Exception {
        String response = okAddress("conflicting-city", "12345", "W SAMPLE ST", 42.3, -83.2, false)
                .replace("],\"geometry\"", ",{\"long_name\":\"OTHER CITY\",\"short_name\":\"OTHER CITY\","
                        + "\"types\":[\"locality\"]}],\"geometry\"");
        HttpServer server = server(exchange -> respond(exchange, response));
        server.start();
        try {
            PlaceCandidate result = client(server, 10, 10, 1, 1).batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST, SAMPLE CITY, MI", "route.png", 0))).getFirst();
            assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            assertNull(result.pid());
        } finally { server.stop(0); }
    }

    @Test
    void rejectsAConflictingUnitFromTheProvider() throws Exception {
        HttpServer server = server(exchange -> respond(exchange,
                okUnitAddress("wrong-unit", "12345", "W SAMPLE ST", "999",
                        42.3, -83.2, "SAMPLE CITY", "MI")));
        server.start();
        try {
            PlaceCandidate result = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST APT 212, SAMPLE CITY, MI", "scan.png", 0))).getFirst();

            assertNull(result.pid());
            assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
            assertEquals("unit_conflict", result.resolution().reasonCode());
            assertEquals(1, result.resolution().attempts());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void marksEquallySpecificProviderDestinationsAmbiguous() throws Exception {
        HttpServer server = server(exchange -> {
            String first = okAddress("first", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                    "SAMPLE CITY", "MI");
            String second = okAddress("second", "12345", "W SAMPLE ST", 42.31, -83.21, false,
                    "SAMPLE CITY", "MI");
            String firstResult = first.substring(first.indexOf('[') + 1, first.lastIndexOf(']'));
            String secondResult = second.substring(second.indexOf('[') + 1, second.lastIndexOf(']'));
            respond(exchange, "{\"status\":\"OK\",\"results\":[" + firstResult + "," + secondResult + "]}");
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 0))).getFirst();

            assertNull(result.pid());
            assertEquals(LocationResolution.Status.AMBIGUOUS, result.resolution().status());
            assertEquals("multiple_destinations", result.resolution().reasonCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retainsAResolvedRowWhenAnotherProviderLookupFails() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            if (requests.incrementAndGet() == 1) {
                respond(exchange, "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"private\"}");
            } else {
                respond(exchange, okAddress("second-row", "54321", "W SAMPLE ST", 42.3, -83.2, false,
                        "SAMPLE CITY", "MI"));
            }
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 10, 10, 1, 1).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 0),
                    place("54321 W SAMPLE ST, SAMPLE CITY, MI", "b.png", 1)));

            assertEquals(2, requests.get());
            assertEquals(LocationResolution.Status.LOOKUP_UNAVAILABLE, results.get(0).resolution().status());
            assertEquals("second-row", results.get(1).pid());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, results.get(1).resolution().status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void releasesBatchAdmissionWhenAnExternalDeadlineHasExpired() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, okAddress("after-deadline", "12345", "W SAMPLE ST", 42.3, -83.2, false,
                    "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 10, 1, 1);
            PlaceCandidate expired = client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 0)), System.nanoTime() - 1)
                    .getFirst();
            PlaceCandidate admitted = client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST, SAMPLE CITY, MI", "b.png", 1))).getFirst();

            assertEquals(LocationResolution.Status.DEFERRED, expired.resolution().status());
            assertEquals(0, expired.resolution().attempts());
            assertEquals(1, requests.get());
            assertEquals("after-deadline", admitted.pid());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsOutOfRangeZeroCoordinatesAndInvalidPlaceIds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            String houseNumber = addressParameter(exchange).split(" ")[0];
            String response = switch (requests.incrementAndGet()) {
                case 1 -> okAddress("valid-id", houseNumber, "W SAMPLE ST", 91, -83.2, false,
                        "SAMPLE CITY", "MI");
                case 2 -> okAddress("valid-id", houseNumber, "W SAMPLE ST", 42.3, -181, false,
                        "SAMPLE CITY", "MI");
                case 3 -> okAddress("valid-id", houseNumber, "W SAMPLE ST", 0, 0, false,
                        "SAMPLE CITY", "MI");
                default -> okAddress("invalid id", houseNumber, "W SAMPLE ST", 42.3, -83.2, false,
                        "SAMPLE CITY", "MI");
            };
            respond(exchange, response);
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 20, 20, 2, 4).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 0),
                    place("12346 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 1),
                    place("12347 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 2),
                    place("12348 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 3)));

            assertEquals(12, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
            assertTrue(results.stream().allMatch(result -> result.resolution().status() == LocationResolution.Status.NO_MATCH));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deduplicatesQueriesAndPreservesEveryStopInInputOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            int request = requests.incrementAndGet();
            lookups.add(addressParameter(exchange));
            String response = request == 1
                    ? ok("first", 42.1, -83.1)
                    : "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
            respond(exchange, response);
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 20, 2, 4);
            String firstAddress = "12345 W SAMPLE ST, SAMPLE CITY, MI";
            String missingAddress = "54321 E OTHER RD, OTHER CITY, OH";
            PlaceCandidate first = place(firstAddress, "z.png", 1);
            PlaceCandidate second = place(missingAddress, "a.png", 2);
            PlaceCandidate repeated = place(firstAddress, "b.png", 3);

            List<PlaceCandidate> result = client.batchGeocode("owner-a", List.of(first, second, repeated));

            assertEquals(4, requests.get());
            assertEquals(List.of(firstAddress, missingAddress, "54321 E OTHER ROAD, OTHER CITY, OH", missingAddress), lookups);
            assertEquals(List.of(firstAddress, missingAddress, firstAddress), result.stream().map(PlaceCandidate::text).toList());
            assertEquals(List.of("z.png", "a.png", "b.png"), result.stream().map(PlaceCandidate::sourceImage).toList());
            assertEquals("first", result.get(0).pid());
            assertNull(result.get(1).pid());
            assertEquals(LocationResolution.Status.NO_MATCH, result.get(1).resolution().status());
            assertEquals("first", result.get(2).pid());
            assertEquals(42.1, result.get(2).lat());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void defersOnlyRowsAfterTheOwnerRequestBudgetIsExhausted() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            String address = addressParameter(exchange);
            requests.incrementAndGet();
            String houseNumber = address.split(" ")[0];
            respond(exchange, okAddress("matched-" + houseNumber, houseNumber, "W SAMPLE ST", 42, -83,
                    false, "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 1, 10, 2, 4).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 1),
                    place("54321 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 2)));

            assertEquals(1, requests.get());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, results.get(0).resolution().status());
            assertEquals(LocationResolution.Status.DEFERRED, results.get(1).resolution().status());
            assertEquals("request_budget_exhausted", results.get(1).resolution().reasonCode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesGlobalHourlyBudgetPerAttemptAndDefersRowsWhenItRunsOut() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            String address = addressParameter(exchange);
            requests.incrementAndGet();
            String houseNumber = address.split(" ")[0];
            respond(exchange, okAddress("matched-" + houseNumber, houseNumber, "W SAMPLE ST", 42, -83,
                    false, "SAMPLE CITY", "MI"));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 2, 2, 4);
            client.batchGeocode("owner-a", List.of(place("12345 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 1)));

            List<PlaceCandidate> results = client.batchGeocode("owner-b", List.of(
                    place("54321 W SAMPLE ST, SAMPLE CITY, MI", "b.png", 1),
                    place("67890 W SAMPLE ST, SAMPLE CITY, MI", "b.png", 2)));

            assertEquals(2, requests.get());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, results.get(0).resolution().status());
            assertEquals(LocationResolution.Status.DEFERRED, results.get(1).resolution().status());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void limitsConcurrentBatchesPerOwnerAndGlobally() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            if (requests.incrementAndGet() == 1) {
                firstRequestStarted.countDown();
                releaseFirstRequest.await(5, TimeUnit.SECONDS);
            }
            respond(exchange, ok("matched", 42, -83));
        });
        server.start();
        var executor = Executors.newSingleThreadExecutor();
        try {
            GeocodingClient client = client(server, 10, 20, 1, 1);
            var firstBatch = executor.submit(() -> client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST", "a.png", 1))));

            assertTrue(firstRequestStarted.await(2, TimeUnit.SECONDS));
            ResponseStatusException ownerRejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-a", List.of(place("54321 W SECOND ST", "b.png", 1))));
            ResponseStatusException globalRejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-b", List.of(place("67890 W THIRD ST", "c.png", 1))));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, ownerRejected.getStatusCode());
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, globalRejected.getStatusCode());
            assertEquals(1, requests.get());
            releaseFirstRequest.countDown();
            assertEquals(1, firstBatch.get(3, TimeUnit.SECONDS).size());
        } finally {
            releaseFirstRequest.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void spacesRequestsByAtLeastSixtyMillisecondsAcrossConcurrentOwners() throws Exception {
        List<Long> requestTimes = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            requestTimes.add(System.nanoTime());
            respond(exchange, ok("matched", 42, -83));
        });
        server.start();
        var executor = Executors.newFixedThreadPool(2);
        try {
            GeocodingClient client = new GeocodingClient("test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", "us");
            var first = executor.submit(() -> client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST", "a.png", 1))));
            var second = executor.submit(() -> client.batchGeocode("owner-b",
                    List.of(place("12345 W SAMPLE ST", "b.png", 1))));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);

            assertEquals(2, requestTimes.size());
            long firstRequest = Math.min(requestTimes.get(0), requestTimes.get(1));
            long secondRequest = Math.max(requestTimes.get(0), requestTimes.get(1));
            assertTrue(secondRequest - firstRequest >= TimeUnit.MILLISECONDS.toNanos(50));
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void rejectsProviderFailureWithoutReturningPrivateProviderDetails() throws Exception {
        HttpServer server = server(exchange -> respond(exchange,
                "{\"status\":\"REQUEST_DENIED\",\"error_message\":\"private provider details\"}"));
        server.start();
        try {
            GeocodingClient client = client(server, 10, 20, 2, 4);
            PlaceCandidate result = client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST, SAMPLE CITY, MI", "a.png", 1))).getFirst();
            assertEquals(LocationResolution.Status.LOOKUP_UNAVAILABLE, result.resolution().status());
            assertFalse(result.resolution().reasonCode().contains("private provider details"));
        } finally {
            server.stop(0);
        }
    }

    private static GeocodingClient client(HttpServer server, int ownerLimit, int globalLimit,
                                          int ownerConcurrency, int globalConcurrency) {
        return new GeocodingClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode",
                "us", ownerLimit, globalLimit, ownerConcurrency, globalConcurrency, 0);
    }

    private static GeocodingClient client(HttpClient httpClient, int ownerLimit, int globalLimit,
                                          int ownerConcurrency, int globalConcurrency) {
        GeocodingClient client = new GeocodingClient("test-key", "http://127.0.0.1:1/geocode", "us",
                ownerLimit, globalLimit, ownerConcurrency, globalConcurrency, 0);
        ReflectionTestUtils.setField(client, "http", httpClient);
        return client;
    }

    private static HttpResponse<String> successfulResponse(String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body);
        return response;
    }

    private static HttpServer server(Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception e) {
                exchange.close();
            }
        });
        return server;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws Exception {
        respond(exchange, 200, body);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String ok(String id, double lat, double lon) {
        return okAddress(id, "12345", "W SAMPLE ST", lat, lon, false);
    }

    private static String okAddress(String id, String streetNumber, String route, double lat, double lon,
            boolean partialMatch) {
        return okAddress(id, streetNumber, route, lat, lon, partialMatch, "SAMPLE CITY", "MI");
    }

    private static String okAddress(String id, String streetNumber, String route, double lat, double lon,
            boolean partialMatch, String locality, String state) {
        String houseNumberComponent = streetNumber == null ? "" : "{\"long_name\":\"" + streetNumber
                + "\",\"short_name\":\"" + streetNumber + "\",\"types\":[\"street_number\"]},";
        String localityComponent = locality == null ? "" : ", {\"long_name\":\"" + locality
                + "\",\"short_name\":\"" + locality + "\",\"types\":[\"locality\",\"political\"]}";
        String stateComponent = state == null ? "" : ", {\"long_name\":\"" + state
                + "\",\"short_name\":\"" + state
                + "\",\"types\":[\"administrative_area_level_1\",\"political\"]}";
        String countryComponent = state == null ? "" : ", {\"long_name\":\"United States\",\"short_name\":\"US\","
                + "\"types\":[\"country\",\"political\"]}";
        return "{\"status\":\"OK\",\"results\":[{\"place_id\":\"" + id + "\",\"partial_match\":"
                + partialMatch + ",\"types\":[\"street_address\"],\"address_components\":["
                + houseNumberComponent + "{\"long_name\":\"" + route + "\",\"short_name\":\"" + route
                + "\",\"types\":[\"route\"]}" + localityComponent + stateComponent + countryComponent
                + "],\"geometry\":{\"location\":{\"lat\":" + lat
                + ",\"lng\":" + lon + "}}}]}";
    }

    private static String okUnitAddress(String id, String streetNumber, String route, String unit,
                                        double lat, double lon, String locality, String state) {
        String response = okAddress(id, streetNumber, route, lat, lon, false, locality, state);
        response = response.replace("\"types\":[\"street_address\"]",
                "\"types\":[\"street_address\",\"subpremise\"]");
        return response.replace("],\"geometry\"", ",{\"long_name\":\"" + unit + "\",\"short_name\":\""
                + unit + "\",\"types\":[\"subpremise\"]}],\"geometry\"");
    }

    private static String okRouteAliases(String id, String streetNumber, String longRoute, String shortRoute,
                                         double lat, double lon, String locality, String state) {
        return okAddress(id, streetNumber, longRoute, lat, lon, false, locality, state)
                .replace("\"long_name\":\"" + longRoute + "\",\"short_name\":\"" + longRoute + "\"",
                        "\"long_name\":\"" + longRoute + "\",\"short_name\":\"" + shortRoute + "\"");
    }

    private static String townshipAddress(String id, String neighborhood, String township) {
        return okAddress(id, "456", "Countryside Lane", 42.3, -83.2, false, null, "MI")
                .replace("],\"geometry\"", ",{\"long_name\":\"" + neighborhood + "\",\"short_name\":\""
                        + neighborhood + "\",\"types\":[\"neighborhood\",\"political\"]},"
                        + "{\"long_name\":\"" + township + "\",\"short_name\":\"" + township
                        + "\",\"types\":[\"administrative_area_level_3\",\"political\"]}],\"geometry\"");
    }

    private static String partialCityResult() {
        return "{\"status\":\"OK\",\"results\":[{\"place_id\":\"city-center\","
                + "\"partial_match\":true,\"types\":[\"locality\",\"political\"],"
                + "\"address_components\":[{\"long_name\":\"Dearborn Heights\","
                + "\"short_name\":\"Dearborn Heights\",\"types\":[\"locality\",\"political\"]}],"
                + "\"geometry\":{\"location\":{\"lat\":42.3,\"lng\":-83.2}}}]}";
    }

    private static String addressParameter(com.sun.net.httpserver.HttpExchange exchange) {
        String encodedAddress = exchange.getRequestURI().getRawQuery().split("&")[0].substring("address=".length());
        return URLDecoder.decode(encodedAddress, StandardCharsets.UTF_8);
    }

    private static PlaceCandidate place(String text, String sourceImage, int lineIndex) {
        return new PlaceCandidate(text, text, sourceImage, lineIndex, 0, 0, null);
    }

    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws Exception;
    }
}
