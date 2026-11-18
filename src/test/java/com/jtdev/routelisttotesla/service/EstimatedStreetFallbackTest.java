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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Public batch-geocoding coverage for independently corroborated road-point estimates. */
class EstimatedStreetFallbackTest {
    private static final String IOWA_TEXT = "543 IOWA, BELLEVILLE, MI";
    private static final String IOWA_DISPLAY = "543 Iowa, Belleville, MI 48111, USA";
    private static final ProviderResult IOWA = new ProviderResult("fixture-iowa-543", "543", "Iowa", "Iowa",
            "Belleville", "48111", 42.1550986, -83.5321065, IOWA_DISPLAY);
    private static final String CEDAR_TEXT = "410 CEDAR, TESTVILLE, MI";
    private static final ProviderResult CEDAR = new ProviderResult("fixture-cedar-410", "410", "Cedar", "Cedar",
            "Testville", "48000", 42.3, -83.2, "410 Cedar, Testville, MI 48000, USA");

    @Test
    void exactLiteralRoadPointNeedsTwoUnchangedIndependentAttestationsAndKeepsOriginalCandidate() throws Exception {
        Probe probe = probe(IOWA_TEXT, phase -> body(IOWA));

        PlaceCandidate result = probe.result();
        LocationResolution resolution = result.resolution();
        assertNotNull(resolution);
        assertEquals(LocationResolution.Status.ESTIMATED_STREET, resolution.status());
        assertFalse(resolution.verified(), "A corroborated road point is still not a verified rooftop");
        assertTrue(resolution.navigable(), "The separately marked estimate may be used as a waypoint");
        assertEquals("corroborated_street_estimate", resolution.strategy());
        assertEquals("corroborated_road_estimate", resolution.reasonCode());
        assertEquals(2, resolution.attempts());
        assertEquals(IOWA_DISPLAY, resolution.matchedAddress());
        assertEquals("fixture-iowa-543", result.pid());
        assertEquals(42.1550986, result.lat(), 0.00000001);
        assertEquals(-83.5321065, result.lon(), 0.00000001);

        assertEquals(2, probe.requests().size());
        assertEquals(IOWA_TEXT, probe.requests().get(0).get("address"));
        assertEquals(IOWA_TEXT, probe.requests().get(1).get("address"));
        assertTrue(probe.requests().get(1).containsKey("components"),
                "The second lookup must constrain the unchanged complete query by locality");
        assertTrue(probe.requests().stream().map(request -> request.getOrDefault("address", ""))
                .noneMatch(value -> value.toLowerCase().contains("iowa street")));

        PlaceCandidate original = place(IOWA_TEXT);
        assertEquals(original.text(), result.text());
        assertEquals(original.normalized(), result.normalized());
        assertEquals(original.sourceImage(), result.sourceImage());
        assertEquals(original.lineIndex(), result.lineIndex());
        assertEquals(original.ocrAgreement(), result.ocrAgreement());
        assertEquals(original.ocrReviewRequired(), result.ocrReviewRequired());
        assertEquals(original.ocrAlternatives(), result.ocrAlternatives());
    }

    @Test
    void literalRoadEstimateIsNotIowaSpecific() throws Exception {
        Probe probe = probe(CEDAR_TEXT, phase -> body(CEDAR));

        assertEstimated(CEDAR_TEXT, CEDAR, probe);
        assertEquals(2, probe.requests().size());
        assertEquals(CEDAR_TEXT, probe.requests().get(0).get("address"));
        assertEquals(CEDAR_TEXT, probe.requests().get(1).get("address"));
        assertTrue(probe.requests().get(1).containsKey("components"));
    }

    @Test
    void oneAttestationOrConflictingAttestationsNeverMakeAnEstimateNavigable() throws Exception {
        Probe missingSecond = probe(IOWA_TEXT, phase -> phase == Phase.ORIGINAL ? body(IOWA) : EMPTY);
        assertHeld(IOWA_TEXT, missingSecond.result());
        assertEquals("street_estimate_unconfirmed", missingSecond.result().resolution().reasonCode());
        assertEquals(IOWA_DISPLAY, missingSecond.result().resolution().matchedAddress());

        List<ProviderResult> conflicting = List.of(
                IOWA.withPlaceId("different-road-point"),
                IOWA.withPoint(42.1553, -83.5324),
                IOWA.withZip("48112"));
        for (ProviderResult second : conflicting) {
            Probe probe = probe(IOWA_TEXT, phase -> body(phase == Phase.ORIGINAL ? IOWA : second));
            assertHeld(IOWA_TEXT, probe.result());
        }

        ProviderResult competitor = IOWA.withPlaceId("competing-road-point").withPoint(42.1553, -83.5324);
        Probe multiple = probe(IOWA_TEXT, phase -> body(List.of(IOWA, competitor)));
        assertHeld(IOWA_TEXT, multiple.result());
        assertTrue(List.of(LocationResolution.Status.NO_MATCH, LocationResolution.Status.AMBIGUOUS)
                        .contains(multiple.result().resolution().status()),
                "Competing road points must stay held");
    }

    @Test
    void incompleteOrStructurallyDifferentEvidenceCannotBecomeAnEstimatedWaypoint() throws Exception {
        List<ProviderResult> invalid = List.of(
                IOWA.withPartial(true),
                IOWA.withExtra(new Component("2", "2", "subpremise")),
                IOWA.withHouse("544"),
                IOWA.withRoute("Ohio", "Ohio"),
                IOWA.withRoute("West Iowa", "W Iowa"),
                IOWA.withLocality("Ypsilanti"),
                IOWA.withState("Ohio", "OH"),
                IOWA.withCountry("Canada", "CA"),
                IOWA.withExtra(new Component("Different Iowa", "Iowa", "route")),
                IOWA.withExtra(new Component("544", "544", "street_number")),
                IOWA.withExtra(new Component("Different City", "Different City", "locality")),
                IOWA.withExtra(new Component("Ohio", "OH", "administrative_area_level_1")),
                IOWA.withExtra(new Component("Canada", "CA", "country")),
                IOWA.withExtra(new Component("48112", "48112", "postal_code")));
        for (ProviderResult result : invalid) {
            Probe probe = probe(IOWA_TEXT, phase -> body(result));
            assertHeld(IOWA_TEXT, probe.result());
        }
        String postalText = "543 IOWA, BELLEVILLE, MI 48111";
        assertHeld(postalText, probe(postalText, phase -> body(IOWA.withZip("48112"))).result());

        String unitText = "543 IOWA APT 2, BELLEVILLE, MI";
        Probe unit = probe(unitText, phase -> body(IOWA.withExtra(new Component("2", "2", "subpremise"))));
        assertHeld(unitText, unit.result());
    }

    @Test
    void exactRooftopProofHasPriorityOverAnInterpolatedLiteralEstimate() throws Exception {
        ProviderResult rooftop = IOWA.withLocationType("ROOFTOP");
        Probe probe = probe(IOWA_TEXT, phase -> body(phase == Phase.ORIGINAL ? IOWA : rooftop));

        PlaceCandidate result = probe.result();
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        assertTrue(result.resolution().verified());
        assertTrue(result.resolution().navigable());
        assertFalse(result.resolution().status() == LocationResolution.Status.ESTIMATED_STREET);
        assertEquals("fixture-iowa-543", result.pid());
        assertEquals(42.1550986, result.lat(), 0.00000001);
        assertEquals(-83.5321065, result.lon(), 0.00000001);
        assertEquals(IOWA_DISPLAY, result.resolution().matchedAddress());
        assertTrue(probe.requests().stream().anyMatch(request -> request.containsKey("components")
                && IOWA_TEXT.equals(request.get("address"))));
        assertTrue(probe.requests().stream().anyMatch(request -> request.containsKey("latlng")),
                "A rooftop fallback requires reverse rooftop corroboration");
    }

    private static void assertEstimated(String text, ProviderResult evidence, Probe probe) {
        LocationResolution resolution = probe.result().resolution();
        assertNotNull(resolution);
        assertEquals(LocationResolution.Status.ESTIMATED_STREET, resolution.status());
        assertFalse(resolution.verified());
        assertTrue(resolution.navigable());
        assertEquals("corroborated_street_estimate", resolution.strategy());
        assertEquals("corroborated_road_estimate", resolution.reasonCode());
        assertEquals(2, resolution.attempts());
        assertEquals(evidence.formatted, resolution.matchedAddress());
        assertEquals(evidence.placeId, probe.result().pid());
        assertEquals(evidence.lat, probe.result().lat(), 0.00000001);
        assertEquals(evidence.lon, probe.result().lon(), 0.00000001);
        assertEquals(text, probe.result().text());
    }

    private static void assertHeld(String text, PlaceCandidate result) {
        assertNotNull(result.resolution());
        assertFalse(result.resolution().verified());
        assertFalse(result.resolution().navigable(), "Uncorroborated or conflicting evidence must not be dispatched");
        assertNotEquals(LocationResolution.Status.ESTIMATED_STREET, result.resolution().status());
        assertTrue(List.of(LocationResolution.Status.NO_MATCH, LocationResolution.Status.AMBIGUOUS)
                        .contains(result.resolution().status()));
        assertNull(result.pid());
        assertEquals(0, result.lat());
        assertEquals(0, result.lon());
        PlaceCandidate original = place(text);
        assertEquals(text, result.text());
        assertEquals(original.normalized(), result.normalized());
        assertEquals(original.sourceImage(), result.sourceImage());
        assertEquals(original.lineIndex(), result.lineIndex());
        assertEquals(original.ocrAgreement(), result.ocrAgreement());
        assertEquals(original.ocrReviewRequired(), result.ocrReviewRequired());
        assertEquals(original.ocrAlternatives(), result.ocrAlternatives());
    }

    private static Probe probe(String text, Function<Phase, String> response) throws Exception {
        List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            try {
                Map<String, String> params = parameters(exchange);
                requests.add(params);
                Phase phase = params.containsKey("latlng") ? Phase.REVERSE
                        : params.containsKey("components") ? Phase.STRUCTURED : Phase.ORIGINAL;
                respond(exchange, response.apply(phase));
            } catch (Exception exception) {
                exchange.close();
            }
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode",
                    "us", 100, 100, 1, 1, 0);
            PlaceCandidate result = client.batchGeocode("owner", List.of(place(text))).getFirst();
            return new Probe(result, List.copyOf(requests));
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) for (String item : query.split("&")) {
            String[] parts = item.split("=", 2);
            params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return params;
    }

    private static void respond(HttpExchange exchange, String response) throws Exception {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String body(ProviderResult result) { return body(List.of(result)); }
    private static String body(List<ProviderResult> results) {
        return "{\"status\":\"OK\",\"results\":[" + String.join(",", results.stream()
                .map(ProviderResult::json).toList()) + "]}";
    }

    private static PlaceCandidate place(String text) {
        return new PlaceCandidate(text, text, "synthetic-road-point.png", 7, 0, 0, null,
                2, true, List.of("543 IOWA", "543 IONA"));
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private enum Phase { ORIGINAL, STRUCTURED, REVERSE }
    private record Probe(PlaceCandidate result, List<Map<String, String>> requests) { }
    private record Component(String longName, String shortName, String type) {
        String json() {
            return "{\"long_name\":" + quote(longName) + ",\"short_name\":" + quote(shortName)
                    + ",\"types\":[" + quote(type) + "]}";
        }
    }

    private static final String EMPTY = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";

    private static final class ProviderResult {
        private final String placeId;
        private final String house;
        private final String routeLong;
        private final String routeShort;
        private final String locality;
        private final String zip;
        private final double lat;
        private final double lon;
        private final String formatted;
        private String stateLong = "Michigan";
        private String stateShort = "MI";
        private String countryLong = "United States";
        private String countryShort = "US";
        private String locationType = "RANGE_INTERPOLATED";
        private boolean partial;
        private final List<Component> extraComponents = new ArrayList<>();

        ProviderResult(String placeId, String house, String routeLong, String routeShort, String locality,
                       String zip, double lat, double lon, String formatted) {
            this.placeId = placeId;
            this.house = house;
            this.routeLong = routeLong;
            this.routeShort = routeShort;
            this.locality = locality;
            this.zip = zip;
            this.lat = lat;
            this.lon = lon;
            this.formatted = formatted;
        }

        ProviderResult copy() {
            ProviderResult copy = new ProviderResult(placeId, house, routeLong, routeShort, locality,
                    zip, lat, lon, formatted);
            copy.stateLong = stateLong;
            copy.stateShort = stateShort;
            copy.countryLong = countryLong;
            copy.countryShort = countryShort;
            copy.locationType = locationType;
            copy.partial = partial;
            copy.extraComponents.addAll(extraComponents);
            return copy;
        }

        ProviderResult withHouse(String value) { ProviderResult copy = copy(); return copy.withBase(value, routeLong, routeShort, locality, zip); }
        ProviderResult withRoute(String longName, String shortName) { ProviderResult copy = copy(); return copy.withBase(house, longName, shortName, locality, zip); }
        ProviderResult withLocality(String value) { ProviderResult copy = copy(); return copy.withBase(house, routeLong, routeShort, value, zip); }
        ProviderResult withZip(String value) { ProviderResult copy = copy(); return copy.withBase(house, routeLong, routeShort, locality, value); }
        ProviderResult withPlaceId(String value) { ProviderResult copy = copy(); return copy.withIdentity(value, lat, lon); }
        ProviderResult withPoint(double latitude, double longitude) { ProviderResult copy = copy(); return copy.withIdentity(placeId, latitude, longitude); }
        ProviderResult withPartial(boolean value) { ProviderResult copy = copy(); copy.partial = value; return copy; }
        ProviderResult withLocationType(String value) { ProviderResult copy = copy(); copy.locationType = value; return copy; }
        ProviderResult withState(String longName, String shortName) { ProviderResult copy = copy(); copy.stateLong = longName; copy.stateShort = shortName; return copy; }
        ProviderResult withCountry(String longName, String shortName) { ProviderResult copy = copy(); copy.countryLong = longName; copy.countryShort = shortName; return copy; }
        ProviderResult withExtra(Component value) { ProviderResult copy = copy(); copy.extraComponents.add(value); return copy; }

        private ProviderResult withBase(String number, String route, String routeAbbr, String city, String postal) {
            return new ProviderResult(placeId, number, route, routeAbbr, city, postal, lat, lon, formatted)
                    .withMetadata(this);
        }
        private ProviderResult withIdentity(String id, double latitude, double longitude) {
            return new ProviderResult(id, house, routeLong, routeShort, locality, zip, latitude, longitude, formatted)
                    .withMetadata(this);
        }
        private ProviderResult withMetadata(ProviderResult source) {
            stateLong = source.stateLong;
            stateShort = source.stateShort;
            countryLong = source.countryLong;
            countryShort = source.countryShort;
            locationType = source.locationType;
            partial = source.partial;
            extraComponents.addAll(source.extraComponents);
            return this;
        }

        private List<Component> components() {
            List<Component> components = new ArrayList<>(List.of(
                    new Component(house, house, "street_number"),
                    new Component(routeLong, routeShort, "route"),
                    new Component(locality, locality, "locality"),
                    new Component(stateLong, stateShort, "administrative_area_level_1"),
                    new Component(countryLong, countryShort, "country"),
                    new Component(zip, zip, "postal_code")));
            components.addAll(extraComponents);
            return components;
        }

        private String json() {
            return "{\"place_id\":" + quote(placeId) + ",\"partial_match\":" + partial
                    + ",\"types\":[\"street_address\"],\"formatted_address\":" + quote(formatted)
                    + ",\"address_components\":[" + String.join(",", components().stream()
                    .map(Component::json).toList()) + "],\"geometry\":{\"location_type\":" + quote(locationType)
                    + ",\"location\":{\"lat\":" + lat + ",\"lng\":" + lon + "}}}";
        }
    }
}
