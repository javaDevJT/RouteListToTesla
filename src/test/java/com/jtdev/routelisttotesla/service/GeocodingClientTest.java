package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeocodingClientTest {
    @Test
    void removesSecondaryUnitOnlyFromLookupAndPreservesOriginalCandidate() throws Exception {
        List<String> lookupAddresses = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = server(exchange -> {
            lookupAddresses.add(addressParameter(exchange));
            respond(exchange, okAddress("canfield", "12345", "W SAMPLE ST", 42.3, -83.2, false));
        });
        server.start();
        try {
            GeocodingClient client = client(server, 10, 20, 2, 4);
            String originalText = "12345 W SAMPLE ST APT 212, SAMPLE CITY, MI";
            PlaceCandidate original = new PlaceCandidate(originalText, "OCR NORMALIZED WITH APT 212", "scan.png",
                    4, 0, 0, null, 2, true, List.of("12345 W SAMPLE ST"));

            PlaceCandidate result = client.batchGeocode("owner-a", List.of(original)).get(0);

            assertEquals(List.of("12345 W SAMPLE ST, SAMPLE CITY, MI"), lookupAddresses);
            assertEquals(originalText, result.text());
            assertEquals(original.normalized(), result.normalized());
            assertEquals(original.sourceImage(), result.sourceImage());
            assertEquals(original.lineIndex(), result.lineIndex());
            assertEquals(original.ocrAgreement(), result.ocrAgreement());
            assertEquals(original.ocrReviewRequired(), result.ocrReviewRequired());
            assertEquals(original.ocrAlternatives(), result.ocrAlternatives());
            assertEquals("canfield", result.pid());
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

            List<PlaceCandidate> results = client(server, 20, 20, 2, 4).batchGeocode("owner-a",
                    originals.stream().map(text -> place(text, "scan.png", 0)).toList());

            assertEquals(expectedLookups, lookupAddresses);
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
                    place("12345 W SAMPLE ST APT 212, SAMPLE CITY, MI", "scan.png", 0),
                    place("12345 W SAMPLE ST", "scan.png", 1),
                    place("12345 W SAMPLE ST APT 213", "scan.png", 2),
                    place("12345 W SAMPLE ST APT 214", "scan.png", 3)));

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
                    place("12345 W SAMPLE ST APT 1", "scan.png", 0),
                    place("12345 W SAMPLE ST APT 2", "scan.png", 1),
                    place("12345 W SAMPLE ST APT 3", "scan.png", 2),
                    place("12345 W SAMPLE ST APT 4", "scan.png", 3)));

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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
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
