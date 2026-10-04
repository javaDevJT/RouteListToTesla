package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
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
                    "http://localhost:" + server.getAddress().getPort() + "/geocode", "us", 3, 3, 1, 1, 60);
            assertEquals("street-address", client.batchGeocode("owner-a",
                    List.of(place("12345 W SAMPLE ST", "warmup.png", 0))).getFirst().pid());
            lookups.clear();
            starts.clear();
            List<PlaceCandidate> result = client.batchGeocode("owner-a", List.of(
                    place(full, "a.png", 4), place(full, "b.png", 7)));

            assertEquals(List.of(full, "12345 W SAMPLE ST, SAMPLE CITY, MI"), lookups);
            assertEquals(List.of(full, full), result.stream().map(PlaceCandidate::text).toList());
            assertEquals(List.of("a.png", "b.png"), result.stream().map(PlaceCandidate::sourceImage).toList());
            assertEquals(List.of(4, 7), result.stream().map(PlaceCandidate::lineIndex).toList());
            assertTrue(result.stream().allMatch(candidate -> "street-address".equals(candidate.pid())));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(starts.get(1) - starts.get(0)) >= 50);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingOrRejectedApartmentMatchFallsBackToAnAcceptedStreetAddress() throws Exception {
        List<String> rejected = List.of(
                "{\"status\":\"ZERO_RESULTS\",\"results\":[]}",
                "{\"status\":\"OK\",\"results\":[]}",
                okAddress("partial", "12345", "W SAMPLE ST", 42.3, -83.2, true),
                okAddress("wrong-house", "12346", "W SAMPLE ST", 42.3, -83.2, false),
                okAddress("missing-number", null, "W SAMPLE ST", 42.3, -83.2, false),
                okAddress("invalid-geometry", "12345", "W SAMPLE ST", 91, -83.2, false),
                okAddress("invalid id", "12345", "W SAMPLE ST", 42.3, -83.2, false));
        AtomicInteger requests = new AtomicInteger();
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            int request = requests.getAndIncrement();
            lookups.add(addressParameter(exchange));
            respond(exchange, request % 2 == 0 ? rejected.get(request / 2)
                    : okAddress("accepted", "12345", "W SAMPLE ST", 42.3, -83.2, false));
        });
        server.start();
        try {
            List<PlaceCandidate> inputs = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            for (int index = 0; index < rejected.size(); index++) {
                String full = "12345 W SAMPLE ST APT " + index + ", SAMPLE CITY, MI";
                inputs.add(place(full, "scan.png", index));
                expected.add(full);
                expected.add("12345 W SAMPLE ST, SAMPLE CITY, MI");
            }
            List<PlaceCandidate> result = client(server, 20, 20, 1, 1).batchGeocode("owner-a", inputs);
            assertEquals(expected, lookups);
            assertTrue(result.stream().allMatch(candidate -> "accepted".equals(candidate.pid())));
            assertEquals(inputs.stream().map(PlaceCandidate::text).toList(), result.stream().map(PlaceCandidate::text).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOnlyOnceAndDoesNotRetryAnAddressWithoutAUnit() throws Exception {
        List<String> lookups = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookups.add(addressParameter(exchange));
            respond(exchange, "{\"status\":\"ZERO_RESULTS\",\"results\":[]}");
        });
        server.start();
        try {
            String full = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
            String noUnit = "12345 W SAMPLE ST  , SAMPLE CITY, MI";
            List<PlaceCandidate> result = client(server, 3, 3, 1, 1).batchGeocode("owner-a",
                    List.of(place(full, "a.png", 0), place(noUnit, "b.png", 1)));
            assertEquals(List.of(full, "12345 W SAMPLE ST, SAMPLE CITY, MI", noUnit), lookups);
            assertTrue(result.stream().allMatch(candidate -> candidate.pid() == null && candidate.lat() == 0 && candidate.lon() == 0));
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
                respond(exchange, okAddress("accepted", "12345", "W SAMPLE ST", 42.3, -83.2, false));
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
        HttpResponse<String> success = successfulResponse(ok("accepted", 42.3, -83.2));
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
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client(server, 3, 3, 1, 1).batchGeocode("owner-a",
                            List.of(place(full, "scan.png", 0))));

            assertEquals(3, requests.get());
            assertEquals(List.of(full, full, full), lookups);
            assertFalse(failure.getMessage().contains("private provider details"));
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

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> client.batchGeocode("owner-a", List.of(place("12345 W SAMPLE ST", "a.png", 0))));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
        verify(httpClient, times(1)).<String>sendAsync(any(HttpRequest.class), any());
        assertEquals("accepted", client.batchGeocode("owner-b",
                List.of(place("12345 W SAMPLE ST", "b.png", 0))).getFirst().pid());
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
                assertThrows(IllegalStateException.class, () -> client.batchGeocode("owner-a", List.of(input)));
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
                ResponseStatusException rejected = assertThrows(ResponseStatusException.class, () -> client.batchGeocode(
                        "owner-a", List.of(place("12345 W SAMPLE ST APT 212", "scan.png", 0))));
                assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
                assertEquals(1, requests.get());
                if (limits[1] > 1) {
                    assertEquals("street-address", client.batchGeocode("owner-b",
                            List.of(place("12345 W SAMPLE ST", "scan.png", 0))).getFirst().pid());
                    assertEquals(2, requests.get());
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
            respond(exchange, okAddress("apartment-address", "12345", "W SAMPLE ST", 42.3, -83.2, false)
                    .replace("\"street_address\"", "\"subpremise\""));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 1, 1, 1, 1);
            String originalText = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
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
        } finally {
            server.stop(0);
        }
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
                    "80 MAIN ST SUITE 5, SAMPLE CITY, MI",
                    "22 MAIN ST UNIT 6, SAMPLE CITY, MI",
                    "55 MAIN ST # 212, SAMPLE CITY, MI",
                    "123 MAIN ST, APT 212, SAMPLE CITY, MI",
                    "123 MAIN ST APT212, SAMPLE CITY, MI",
                    "123 MAIN ST STE.2, SAMPLE CITY, MI",
                    "33 UNIT ROAD, SAMPLE CITY, MI",
                    "123 UNIT 5 ROAD, SAMPLE CITY, MI",
                    "44 APARTMENT LANE, SAMPLE CITY, MI",
                    "12 1/2 MAIN ST APT 5, SAMPLE CITY, MI",
                    "12.5 MAIN ST APT 5, SAMPLE CITY, MI",
                    "12A MAIN ST APT 5, SAMPLE CITY, MI");
            List<String> expectedLookups = List.of(
                    "123 MAIN ST, SAMPLE CITY, MI",
                    "80 MAIN ST, SAMPLE CITY, MI",
                    "80 MAIN ST, SAMPLE CITY, MI",
                    "22 MAIN ST, SAMPLE CITY, MI",
                    "55 MAIN ST, SAMPLE CITY, MI",
                    "123 MAIN ST, SAMPLE CITY, MI",
                    "123 MAIN ST, SAMPLE CITY, MI",
                    "123 MAIN ST, SAMPLE CITY, MI",
                    "33 UNIT ROAD, SAMPLE CITY, MI",
                    "123 UNIT 5 ROAD, SAMPLE CITY, MI",
                    "44 APARTMENT LANE, SAMPLE CITY, MI",
                    "12 1/2 MAIN ST, SAMPLE CITY, MI",
                    "12.5 MAIN ST, SAMPLE CITY, MI",
                    "12A MAIN ST, SAMPLE CITY, MI");

            List<String> expectedRequests = new ArrayList<>();
            for (int index = 0; index < originals.size(); index++) {
                expectedRequests.add(originals.get(index));
                if (!originals.get(index).equals(expectedLookups.get(index))) {
                    expectedRequests.add(expectedLookups.get(index));
                }
            }
            List<PlaceCandidate> results = client(server, 40, 40, 2, 4).batchGeocode("owner-a",
                    originals.stream().map(text -> place(text, "scan.png", 0)).toList());

            assertEquals(expectedRequests, lookupAddresses);
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
            List<PlaceCandidate> results = client(server, 10, 20, 2, 4).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, SAMPLE CITY, MI", "scan.png", 0),
                    place("12345 W SAMPLE ST", "scan.png", 1),
                    place("12345 W SAMPLE ST, SECOND CITY, MI", "scan.png", 2),
                    place("12345 W SAMPLE ST, THIRD CITY, MI", "scan.png", 3)));

            assertEquals(4, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
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
    void rejectsOutOfRangeZeroCoordinatesAndInvalidPlaceIds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            String response = switch (requests.incrementAndGet()) {
                case 1 -> okAddress("valid-id", "12345", "W SAMPLE ST", 91, -83.2, false);
                case 2 -> okAddress("valid-id", "12345", "W SAMPLE ST", 42.3, -181, false);
                case 3 -> okAddress("valid-id", "12345", "W SAMPLE ST", 0, 0, false);
                default -> okAddress("invalid id", "12345", "W SAMPLE ST", 42.3, -83.2, false);
            };
            respond(exchange, response);
        });
        server.start();
        try {
            List<PlaceCandidate> results = client(server, 10, 20, 2, 4).batchGeocode("owner-a", List.of(
                    place("12345 W SAMPLE ST, FIRST CITY, MI", "scan.png", 0),
                    place("12345 W SAMPLE ST, SECOND CITY, MI", "scan.png", 1),
                    place("12345 W SAMPLE ST, THIRD CITY, MI", "scan.png", 2),
                    place("12345 W SAMPLE ST, FOURTH CITY, MI", "scan.png", 3)));

            assertEquals(4, requests.get());
            assertTrue(results.stream().allMatch(result -> result.pid() == null && result.lat() == 0 && result.lon() == 0));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void deduplicatesQueriesAndPreservesEveryStopInInputOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int request = requests.incrementAndGet();
            String response = request == 1
                    ? ok("first", 42.1, -83.1)
                    : "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
            respond(exchange, response);
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 20, 2, 4);
            PlaceCandidate first = place("first", "z.png", 1);
            PlaceCandidate second = place("missing", "a.png", 2);
            PlaceCandidate repeated = place("first", "b.png", 3);

            List<PlaceCandidate> result = client.batchGeocode("owner-a", List.of(first, second, repeated));

            assertEquals(2, requests.get());
            assertEquals(List.of("first", "missing", "first"), result.stream().map(PlaceCandidate::text).toList());
            assertEquals(List.of("z.png", "a.png", "b.png"), result.stream().map(PlaceCandidate::sourceImage).toList());
            assertEquals("first", result.get(0).pid());
            assertNull(result.get(1).pid());
            assertEquals("first", result.get(2).pid());
            assertEquals(42.1, result.get(2).lat());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reservesTheWholeBatchBeforeSendingItsFirstQuery() throws Exception {
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
            GeocodingClient client = client(server, 2, 4, 2, 4);
            var firstBatch = executor.submit(() -> client.batchGeocode("owner-a",
                    List.of(place("one", "a.png", 1), place("two", "a.png", 2))));

            assertTrue(firstRequestStarted.await(2, TimeUnit.SECONDS));
            ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-a", List.of(place("three", "b.png", 1))));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
            assertEquals(1, requests.get());

            releaseFirstRequest.countDown();
            assertEquals(2, firstBatch.get(3, TimeUnit.SECONDS).size());
            assertEquals(2, requests.get());
        } finally {
            releaseFirstRequest.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void enforcesGlobalHourlyBudgetAcrossOwnersBeforeExternalCalls() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            respond(exchange, ok("matched", 42, -83));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 2, 2, 4);
            client.batchGeocode("owner-a", List.of(place("one", "a.png", 1)));

            ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-b", List.of(
                            place("two", "b.png", 1), place("three", "b.png", 2))));

            assertEquals(HttpStatus.TOO_MANY_REQUESTS, rejected.getStatusCode());
            assertEquals(1, requests.get());
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
            var firstBatch = executor.submit(() -> client.batchGeocode("owner-a", List.of(place("one", "a.png", 1))));

            assertTrue(firstRequestStarted.await(2, TimeUnit.SECONDS));
            ResponseStatusException ownerRejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-a", List.of(place("two", "b.png", 1))));
            ResponseStatusException globalRejected = assertThrows(ResponseStatusException.class,
                    () -> client.batchGeocode("owner-b", List.of(place("three", "c.png", 1))));

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
            var first = executor.submit(() -> client.batchGeocode("owner-a", List.of(place("one", "a.png", 1))));
            var second = executor.submit(() -> client.batchGeocode("owner-b", List.of(place("two", "b.png", 1))));
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
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> client.batchGeocode("owner-a", List.of(place("first", "a.png", 1))));
            assertFalse(failure.getMessage().contains("private provider details"));
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
        String houseNumberComponent = streetNumber == null ? "" : "{\"long_name\":\"" + streetNumber
                + "\",\"short_name\":\"" + streetNumber + "\",\"types\":[\"street_number\"]},";
        return "{\"status\":\"OK\",\"results\":[{\"place_id\":\"" + id + "\",\"partial_match\":"
                + partialMatch + ",\"types\":[\"street_address\"],\"address_components\":["
                + houseNumberComponent + "{\"long_name\":\"" + route + "\",\"short_name\":\"" + route
                + "\",\"types\":[\"route\"]}],\"geometry\":{\"location\":{\"lat\":" + lat
                + ",\"lng\":" + lon + "}}}]}";
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
