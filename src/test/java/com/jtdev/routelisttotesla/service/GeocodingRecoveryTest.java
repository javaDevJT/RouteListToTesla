package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GeocodingRecoveryTest {
    private static final String EMPTY = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";

    @Test
    void corroboratesStreetRepresentationsAcrossNamesStatesAndUnitsWithoutChangingInput() throws Exception {
        for (String[] pair : List.of(new String[]{"MEADOW CT", "Meadow Drive"},
                new String[]{"COUNTRY SIDE LN", "Countryside Lane"},
                new String[]{"N LAKE VIEW RD NE", "North Lakeview Street Northeast"},
                new String[]{"OAK PARK ST", "Oak Park Avenue"})) {
            for (String state : List.of("MI", "NY", "CA")) {
                for (String unit : new String[]{null, "330", "A208"}) {
                    String requested = "12345 " + pair[0] + (unit == null ? "" : " APT " + unit) + ", TESTVILLE, " + state;
                    String body = roof("precise", "12345", pair[1], "TESTVILLE", state, unit, 42.3, -83.2);
                    List<Map<String, String>> requests = new ArrayList<>();
                    HttpServer server = server(exchange -> { requests.add(parameters(exchange)); respond(exchange, body); });
                    server.start();
                    try {
                        PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place(requested))).getFirst();
                        assertEquals("precise", result.pid(), requested);
                        assertEquals(unit == null ? LocationResolution.Status.VERIFIED_BUILDING : LocationResolution.Status.VERIFIED_UNIT,
                                result.resolution().status(), requested);
                        assertEquals("corroborated_street_representation", result.resolution().strategy(), requested);
                        assertEquals(3, result.resolution().attempts());
                        assertEquals(requested, result.text());
                        assertNotNull(result.resolution().matchedAddress());
                        assertEquals(requested, requests.getFirst().get("address"));
                        assertEquals("12345 " + pair[0] + (unit == null ? "" : " UNIT " + unit), requests.get(1).get("address"));
                        assertEquals("12345 " + pair[1] + (unit == null ? "" : " UNIT " + unit), requests.get(2).get("address"));
                        assertEquals("locality:TESTVILLE|administrative_area:" + state + "|country:US", requests.get(1).get("components"));
                    } finally { server.stop(0); }
                }
            }
        }
    }

    @Test
    void postalLocalityRecoveryRequiresTheSameFullStreetWithinTenMetres() throws Exception {
        for (String city : List.of("TESTVILLE", "SAMPLE BOROUGH", "NEW TOWN")) {
            String original = roof("rooftop", "12345", "Sample Road", "POSTAL CITY", "MI", null, 42.3, -83.2);
            String reverse = roof("interpolated", "12345", "Sample Road", city, "MI", null, 42.30001, -83.2)
                    .replace("ROOFTOP", "RANGE_INTERPOLATED");
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server(exchange -> {
                calls.incrementAndGet(); respond(exchange, parameters(exchange).containsKey("latlng") ? reverse : original);
            });
            server.start();
            try {
                String requested = "12345 SAMPLE RD, " + city + ", MI";
                PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place(requested))).getFirst();
                assertEquals("rooftop", result.pid(), "Use the precise original location, not the interpolated proof");
                assertEquals(42.3, result.lat());
                assertEquals(requested, result.text());
                assertEquals("corroborated_locality", result.resolution().strategy());
                assertEquals(2, calls.get());
            } finally { server.stop(0); }
        }
    }

    @Test
    void neverProposesRepairsForChangedIdentityConflictsOrNonPreciseResults() throws Exception {
        String valid = roof("precise", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        for (String invalid : List.of(
                valid.replace("12345", "12346"), valid.replace("Sample Drive", "Different Drive"),
                valid.replace("Sample Drive", "North Sample Drive"),
                valid.replace("TESTVILLE", "OTHER CITY"), valid.replace("\"MI\"", "\"OH\""),
                valid.replace("United States", "Canada").replace("\"US\"", "\"CA\""),
                valid.replace("\"partial_match\":false", "\"partial_match\":true"),
                valid.replace("ROOFTOP", "RANGE_INTERPOLATED"), valid.replace("ROOFTOP", "GEOMETRIC_CENTER"),
                valid.replace("\"street_address\"", "\"locality\""),
                valid.replace("42.3", "142.3"), valid.replace("\"precise\"", "\"bad place id\""),
                valid.replace("],\"geometry\"", "," + component("Different Drive", "route") + "],\"geometry\""),
                valid.replace("],\"geometry\"", "," + component("OTHER CITY", "locality") + "],\"geometry\""))) {
            List<Map<String, String>> requests = new ArrayList<>();
            HttpServer server = server(exchange -> { requests.add(parameters(exchange)); respond(exchange, invalid); });
            server.start();
            try {
                PlaceCandidate result = client(server, 100).batchGeocode("owner",
                        List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
                assertNull(result.pid(), invalid);
                assertTrue(requests.stream().noneMatch(q -> q.containsKey("latlng")
                        || q.getOrDefault("address", "").equals("12345 Sample Drive")), invalid);
            } finally { server.stop(0); }
        }
    }

    @Test
    void mismatchedProofCannotAuthorizeACorrectionOrDiscardApartmentConflicts() throws Exception {
        String original = roof("precise", "12345", "Sample Drive", "TESTVILLE", "MI", "330", 42.3, -83.2);
        for (String proof : List.of(EMPTY,
                original.replace("\"precise\"", "\"other-building\""),
                original.replace("42.3", "42.3001"),
                original.replace("\"330\"", "\"331\""),
                original.replace("12345", "12346"), original.replace("Sample Drive", "Other Drive"),
                original.replace("ROOFTOP", "RANGE_INTERPOLATED"),
                original.replace("\"partial_match\":false", "\"partial_match\":true"),
                original.replace("TESTVILLE", "OTHER CITY"))) {
            HttpServer server = server(exchange -> respond(exchange,
                    parameters(exchange).getOrDefault("address", "").equals("12345 Sample Drive UNIT 330") ? proof : original));
            server.start();
            try {
                String text = "12345 SAMPLE CT APT 330, TESTVILLE, MI";
                PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place(text))).getFirst();
                assertNull(result.pid(), proof);
                assertEquals(text, result.text());
                assertTrue(result.resolution().attempts() <= 8);
            } finally { server.stop(0); }
        }
    }

    @Test
    void reverseProofCannotBorrowNearbyWrongStreetUnitOrCity() throws Exception {
        String original = roof("precise", "12345", "Sample Road", "POSTAL CITY", "MI", "330", 42.3, -83.2);
        String valid = roof("reverse", "12345", "Sample Road", "TESTVILLE", "MI", "330", 42.30001, -83.2);
        for (String invalid : List.of(valid.replace("42.30001", "42.301"), valid.replace("12345", "12346"),
                valid.replace("Sample Road", "Sample Drive"), valid.replace("TESTVILLE", "OTHER CITY"),
                valid.replace("\"330\"", "\"331\""), valid.replace("ROOFTOP", "APPROXIMATE"),
                valid.replace("\"street_address\"", "\"locality\""))) {
            HttpServer server = server(exchange -> respond(exchange, parameters(exchange).containsKey("latlng") ? invalid : original));
            server.start();
            try {
                PlaceCandidate result = client(server, 100).batchGeocode("owner",
                        List.of(place("12345 SAMPLE RD APT 330, TESTVILLE, MI"))).getFirst();
                assertNull(result.pid(), invalid);
            } finally { server.stop(0); }
        }
    }

    @Test
    void canonicalSelfConfirmationCannotOverrideAConflictingOriginalStreetProof() throws Exception {
        String proposed = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        String actualCourt = roof("court", "12345", "Sample Court", "TESTVILLE", "MI", null, 42.31, -83.2);
        List<Map<String, String>> requests = new ArrayList<>();
        HttpServer server = server(exchange -> {
            Map<String, String> query = parameters(exchange); requests.add(query);
            respond(exchange, query.getOrDefault("address", "").equals("12345 SAMPLE CT") ? actualCourt : proposed);
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 100).batchGeocode("owner",
                    List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
            assertNull(result.pid(), "The proposed Drive must not replace a separately located Court");
            assertTrue(requests.stream().noneMatch(q -> q.getOrDefault("address", "").equals("12345 Sample Drive")));
        } finally { server.stop(0); }
    }

    @Test
    void distantReverseUnitCannotCreateAConflictAtTheRequestedBuilding() throws Exception {
        String original = roof("precise", "12345", "Sample Road", "POSTAL CITY", "MI", "330", 42.3, -83.2);
        String nearbyBuilding = roof("nearby", "12345", "Sample Road", "TESTVILLE", "MI", null, 42.30001, -83.2);
        String distantUnit = roof("far", "12345", "Sample Road", "TESTVILLE", "MI", "331", 42.31, -83.2);
        String reverse = combine(nearbyBuilding, distantUnit);
        HttpServer server = server(exchange -> respond(exchange, parameters(exchange).containsKey("latlng") ? reverse : original));
        server.start();
        try {
            String text = "12345 SAMPLE RD APT 330, TESTVILLE, MI";
            PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place(text))).getFirst();
            assertEquals("precise", result.pid());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
            assertEquals("unit_not_confirmed", result.resolution().reasonCode());
            assertEquals(text, result.text());
        } finally { server.stop(0); }
    }

    @Test
    void originalStreetProofRejectsCompetingDestinationsInEitherOrder() throws Exception {
        String proposed = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        String court = roof("court", "12345", "Sample Court", "TESTVILLE", "MI", null, 42.31, -83.2);
        for (String proof : List.of(combine(proposed, court), combine(court, proposed))) {
            AtomicInteger calls = new AtomicInteger();
            HttpServer server = server(exchange -> {
                calls.incrementAndGet(); respond(exchange, parameters(exchange).getOrDefault("address", "").equals("12345 SAMPLE CT") ? proof : proposed);
            });
            server.start();
            try {
                PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
                assertEquals(LocationResolution.Status.AMBIGUOUS, result.resolution().status());
                assertNull(result.pid());
                assertEquals(2, calls.get(), "Do not issue canonical proof after competing original destinations");
            } finally { server.stop(0); }
        }
    }

    @Test
    void originalAndCanonicalProofRetriesShareTheEightRequestStopCeiling() throws Exception {
        String body = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1 || call == 4) respond(exchange, body);
            else if (call == 7) respond(exchange, EMPTY);
            else { byte[] bytes = new byte[0]; exchange.sendResponseHeaders(503, -1); exchange.close(); }
        });
        server.start();
        try {
            PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
            assertEquals(LocationResolution.Status.DEFERRED, result.resolution().status());
            assertEquals("request_budget_exhausted", result.resolution().reasonCode());
            assertEquals(8, calls.get());
            assertEquals(8, result.resolution().attempts());
            assertNull(result.pid());
        } finally { server.stop(0); }
    }

    @Test
    void proofProviderFailureIsVisibleAndIncompleteLocalityCannotAuthorizeRecovery() throws Exception {
        String body = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        AtomicInteger calls = new AtomicInteger();
        HttpServer denied = server(exchange -> respond(exchange, calls.incrementAndGet() == 1 ? body :
                "{\"status\":\"REQUEST_DENIED\",\"results\":[]}"));
        denied.start();
        try {
            PlaceCandidate result = client(denied, 100).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
            assertEquals(LocationResolution.Status.LOOKUP_UNAVAILABLE, result.resolution().status());
            assertEquals("provider_rejected", result.resolution().reasonCode());
            assertNull(result.pid());
            assertEquals(2, calls.get());
        } finally { denied.stop(0); }
        List<Map<String, String>> queries = new ArrayList<>();
        HttpServer noState = server(exchange -> { queries.add(parameters(exchange)); respond(exchange, body); });
        noState.start();
        try {
            PlaceCandidate result = client(noState, 100).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE"))).getFirst();
            assertNull(result.pid());
            assertTrue(queries.stream().noneMatch(q -> q.containsKey("latlng") || q.getOrDefault("address", "").equals("12345 Sample Drive")));
        } finally { noState.stop(0); }
    }

    @Test
    void missingUnitInProofRetainsApartmentButVerifiesOnlyTheBuilding() throws Exception {
        String original = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", "330", 42.3, -83.2);
        String building = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        HttpServer server = server(exchange -> respond(exchange,
                parameters(exchange).getOrDefault("address", "").contains(",") ? original : building));
        server.start();
        try {
            String text = "12345 SAMPLE CT APT 330, TESTVILLE, MI";
            PlaceCandidate result = client(server, 100).batchGeocode("owner", List.of(place(text))).getFirst();
            assertEquals("drive", result.pid());
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
            assertEquals("unit_not_confirmed", result.resolution().reasonCode());
            assertEquals("corroborated_street_representation", result.resolution().strategy());
            assertEquals(text, result.text());
        } finally { server.stop(0); }
    }

    @Test
    void realApartmentConflictsStopBeforeAnyRepresentationProof() throws Exception {
        String conflicting = roof("drive", "12345", "Sample Drive", "TESTVILLE", "MI", "331", 42.3, -83.2);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {calls.incrementAndGet(); respond(exchange, conflicting);});
        server.start();
        try {
            PlaceCandidate result = client(server, 100).batchGeocode("owner",
                    List.of(place("12345 SAMPLE CT APT 330, TESTVILLE, MI"))).getFirst();
            assertNull(result.pid());
            assertEquals("unit_conflict", result.resolution().reasonCode());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    @Test
    void multipleRecoveryCandidatesRemainAmbiguousAndProofConsumesTheSharedBudget() throws Exception {
        String first = roof("one", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3, -83.2);
        String second = roof("two", "12345", "Sample Drive", "TESTVILLE", "MI", null, 42.3001, -83.2);
        // Combine complete result objects without changing their address components.
        String body = combine(first, second);
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> { calls.incrementAndGet(); respond(exchange, body); });
        server.start();
        try {
            PlaceCandidate ambiguous = client(server, 100).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
            assertEquals(LocationResolution.Status.AMBIGUOUS, ambiguous.resolution().status());
            assertNull(ambiguous.pid());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
        calls.set(0);
        server = server(exchange -> { calls.incrementAndGet(); respond(exchange, first); });
        server.start();
        try {
            PlaceCandidate limited = client(server, 1).batchGeocode("owner", List.of(place("12345 SAMPLE CT, TESTVILLE, MI"))).getFirst();
            assertEquals(LocationResolution.Status.DEFERRED, limited.resolution().status());
            assertNull(limited.pid());
            assertEquals(1, limited.resolution().attempts());
            assertEquals(1, calls.get());
        } finally { server.stop(0); }
    }

    private static PlaceCandidate place(String text) { return new PlaceCandidate(text, text, "synthetic.png", 0, 0, 0, null); }
    private static GeocodingClient client(HttpServer server, int budget) {
        return new GeocodingClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", "us", budget, budget, 1, 1, 0);
    }
    private interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private static HttpServer server(Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> { try { handler.handle(exchange); } catch (Exception e) { exchange.close(); } });
        return server;
    }
    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String item : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] parts = item.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return params;
    }
    private static void respond(HttpExchange exchange, String response) throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    private static String component(String name, String type) {
        return "{\"long_name\":\"" + name + "\",\"short_name\":\"" + name + "\",\"types\":[\"" + type + "\"]}";
    }
    private static String combine(String first, String second) throws Exception {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var body = mapper.readTree(first).deepCopy();
        ((tools.jackson.databind.node.ArrayNode) body.path("results")).add(mapper.readTree(second).path("results").get(0));
        return body.toString();
    }
    private static String roof(String id, String house, String route, String city, String state, String unit, double lat, double lon) {
        return "{\"status\":\"OK\",\"results\":[{\"place_id\":\"" + id + "\",\"partial_match\":false,\"types\":[\"street_address\"],"
                + "\"formatted_address\":\"" + house + " " + route + ", " + city + ", " + state + "\",\"address_components\":["
                + component(house, "street_number") + "," + component(route, "route") + "," + component(city, "locality") + ","
                + component(state, "administrative_area_level_1") + "," + component("United States", "country").replace("\"short_name\":\"United States\"", "\"short_name\":\"US\"")
                + (unit == null ? "" : "," + component(unit, "subpremise"))
                + "],\"geometry\":{\"location_type\":\"ROOFTOP\",\"location\":{\"lat\":" + lat + ",\"lng\":" + lon + "}}}]}";
    }
}
