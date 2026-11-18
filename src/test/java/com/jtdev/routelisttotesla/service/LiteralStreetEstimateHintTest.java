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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Public batch-geocoding coverage for literal, suffixless street estimate hints. */
class LiteralStreetEstimateHintTest {
    private static final String IOWA_TEXT = "543 IOWA, BELLEVILLE, MI";
    private static final String IOWA_DISPLAY = "543 Iowa, Belleville, MI 48111, USA";
    private static final Address IOWA = new Address(IOWA_TEXT, "543", "IOWA", "BELLEVILLE", "MI", null,
            provider("iowa-belleville", "543", "Iowa", "Iowa", "Belleville", "48111",
                    42.1550986, -83.5321065, IOWA_DISPLAY));
    // This distinct street name prevents the intended behavior from being an Iowa-specific exception.
    private static final Address CEDAR = new Address("410 CEDAR, TESTVILLE, MI", "410", "CEDAR",
            "TESTVILLE", "MI", null, provider("cedar-testville", "410", "Cedar", "Cedar", "Testville",
            "48000", 42.3, -83.2, "410 Cedar, Testville, MI 48000, USA"));

    @Test
    void uncorroboratedLiteralIowaInterpolationRemainsAnEditingHint() throws Exception {
        Probe probe = probe(IOWA, phase -> phase == Phase.ORIGINAL ? body(IOWA.provider()) : EMPTY);

        assertLiteralHint(IOWA, probe.result(), "street_estimate_unconfirmed");
        assertEquals(2, probe.requests().size(), "The original and constrained public lookups should suffice");
        assertEquals(IOWA_TEXT, probe.requests().getFirst().get("address"));
        assertTrue(probe.requests().get(1).containsKey("components"),
                "The second lookup should retain its full locality constraint");
        assertEquals(IOWA_TEXT, probe.requests().get(1).get("address"),
                "The constrained query must not invent a suffix or rewrite the input");
        assertTrue(probe.requests().stream().map(request -> request.getOrDefault("address", ""))
                .noneMatch(value -> value.toLowerCase().contains("iowa street")));
    }

    @Test
    void literalSuffixlessHintWorksForASecondStreetName() throws Exception {
        Probe probe = probe(CEDAR, phase -> phase == Phase.ORIGINAL ? body(CEDAR.provider()) : EMPTY);

        assertLiteralHint(CEDAR, probe.result(), "street_estimate_unconfirmed");
        assertEquals(2, probe.requests().size());
    }

    @Test
    void literalEstimateDoesNotAcceptPartialOrStructurallyDifferentResults() throws Exception {
        ProviderResult valid = IOWA.provider();
        for (ProviderResult invalid : List.of(
                valid.withPartial(true),
                valid.withHouse("544"),
                valid.withRoute("Ohio", "Ohio"),
                valid.withRoute("West Iowa", "W Iowa"),
                valid.withLocality("Ypsilanti"),
                valid.withState("Ohio", "OH"),
                valid.withCountry("Canada", "CA"),
                valid.withLocality(null).withExtra(new Component("Belleville", "Belleville",
                        "administrative_area_level_3")),
                valid.withPoint(91, -83.5321065),
                valid.withPoint(42.1550986, 181),
                valid.withPoint(0, 0),
                valid.withFormattedAddress(""),
                valid.withFormattedAddress("X".repeat(513)),
                valid.withTypes("route"),
                valid.withExtra(new Component("Second Iowa", "Iowa", "route")),
                valid.withExtra(new Component("544", "544", "street_number")),
                valid.withExtra(new Component("Different City", "Different City", "locality")),
                valid.withExtra(new Component("Ohio", "OH", "administrative_area_level_1")),
                valid.withExtra(new Component("Canada", "CA", "country")),
                valid.withExtra(new Component("48112", "48112", "postal_code")))) {
            assertNoLiteralHint(IOWA, phase -> body(invalid));
        }
    }

    @Test
    void literalEstimateRequiresUnitlessInputAndNoSubpremiseEvidence() throws Exception {
        ProviderResult valid = IOWA.provider();
        Address unitInput = new Address("543 IOWA APT 2, BELLEVILLE, MI", "543", "IOWA",
                "BELLEVILLE", "MI", "2", valid.withExtra(new Component("2", "2", "subpremise")));
        assertNoLiteralHint(unitInput, phase -> body(unitInput.provider()));
        assertNoLiteralHint(IOWA, phase -> body(valid.withExtra(new Component("2", "2", "subpremise"))));
        assertNoLiteralHint(IOWA, phase -> body(valid.withExtra(new Component("", "", "subpremise"))));
    }

    @Test
    void multiplePlausibleLiteralEstimatesStayAmbiguous() throws Exception {
        ProviderResult rival = IOWA.provider().withPlaceId("other-iowa-destination")
                .withPoint(42.1552, -83.5322);
        Probe probe = probe(IOWA, phase -> body(List.of(IOWA.provider(), rival)));

        assertEquals(LocationResolution.Status.AMBIGUOUS, probe.result().resolution().status());
        assertFalse(probe.result().resolution().navigable());
        assertNull(probe.result().pid());
        assertEquals(0, probe.result().lat());
        assertEquals(0, probe.result().lon());
        assertNull(probe.result().resolution().matchedAddress());
    }

    @Test
    void existingMissingSuffixNorfolkHintKeepsItsEstablishedReasonCode() throws Exception {
        Address norfolk = new Address("13342 S NORFOLK, DETROIT, MI", "13342", "S NORFOLK", "DETROIT", "MI", null,
                provider("norfolk-detroit", "13342", "South Norfolk Court", "S Norfolk Ct", "Detroit", "48235",
                        42.4430414, -83.179406, "13342 S Norfolk Ct, Detroit, MI 48235, USA")
                        .withLocationType("RANGE_INTERPOLATED"));
        Probe probe = probe(norfolk, phase -> phase == Phase.ORIGINAL ? body(norfolk.provider()) : EMPTY);

        assertLiteralHint(norfolk, probe.result(), "street_location_estimated");
    }

    private static void assertLiteralHint(Address address, PlaceCandidate result, String reason) {
        PlaceCandidate input = place(address.text());
        assertNull(result.pid(), "An uncorroborated estimate must not become a navigation destination");
        assertEquals(0, result.lat());
        assertEquals(0, result.lon());
        assertEquals(input.text(), result.text());
        assertEquals(input.normalized(), result.normalized());
        assertEquals(input.sourceImage(), result.sourceImage());
        assertEquals(input.lineIndex(), result.lineIndex());
        assertEquals(input.ocrAgreement(), result.ocrAgreement());
        assertEquals(input.ocrReviewRequired(), result.ocrReviewRequired());
        assertEquals(input.ocrAlternatives(), result.ocrAlternatives());
        assertNotNull(result.resolution());
        assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
        assertEquals("recovery_exhausted", result.resolution().strategy());
        assertEquals(reason, result.resolution().reasonCode());
        assertEquals("street-address-v20", result.resolution().policyVersion());
        assertEquals(address.provider().formattedAddress(), result.resolution().matchedAddress());
    }

    private static void assertNoLiteralHint(Address address, Function<Phase, String> response) throws Exception {
        assertNoLiteralHintResult(address, probe(address, response).result());
    }

    private static void assertNoLiteralHintResult(Address address, PlaceCandidate result) {
        PlaceCandidate input = place(address.text());
        assertNull(result.pid(), "An unverified estimate must not become a navigation destination");
        assertEquals(0, result.lat());
        assertEquals(0, result.lon());
        assertEquals(input.text(), result.text());
        assertEquals(input.normalized(), result.normalized());
        assertEquals(input.sourceImage(), result.sourceImage());
        assertEquals(input.lineIndex(), result.lineIndex());
        assertEquals(input.ocrAgreement(), result.ocrAgreement());
        assertEquals(input.ocrReviewRequired(), result.ocrReviewRequired());
        assertEquals(input.ocrAlternatives(), result.ocrAlternatives());
        assertNotNull(result.resolution());
        assertEquals(LocationResolution.Status.NO_MATCH, result.resolution().status());
        assertNotEquals("street_estimate_unconfirmed", result.resolution().reasonCode());
        assertNotEquals("street_location_estimated", result.resolution().reasonCode());
    }

    private static Probe probe(Address address, Function<Phase, String> response) throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
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
            PlaceCandidate result = client.batchGeocode("owner", List.of(place(address.text()))).getFirst();
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
        return new PlaceCandidate(text, text, "synthetic-iowa.png", 7, 0, 0, null,
                2, true, List.of("543 IOWA", "543 IONA"));
    }

    private static ProviderResult provider(String id, String house, String routeLong, String routeShort,
                                          String city, String zip, double lat, double lon, String display) {
        return new ProviderResult(id, house, routeLong, routeShort, city, zip, lat, lon, display);
    }

    private static String quote(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private enum Phase { ORIGINAL, STRUCTURED, REVERSE }
    private record Probe(PlaceCandidate result, List<Map<String, String>> requests) { }
    private record Address(String text, String house, String street, String city, String state, String unit,
                           ProviderResult provider) { }
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
        private List<String> resultTypes = new ArrayList<>(List.of("street_address"));
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

        String routeLong() { return routeLong; }
        String formattedAddress() { return formatted; }

        ProviderResult copy() {
            ProviderResult copy = new ProviderResult(placeId, house, routeLong, routeShort, locality, zip,
                    lat, lon, formatted);
            copy.stateLong = stateLong;
            copy.stateShort = stateShort;
            copy.countryLong = countryLong;
            copy.countryShort = countryShort;
            copy.locationType = locationType;
            copy.partial = partial;
            copy.resultTypes = new ArrayList<>(resultTypes);
            copy.extraComponents.addAll(extraComponents);
            return copy;
        }

        ProviderResult withHouse(String value) {
            return new ProviderResult(placeId, value, routeLong, routeShort, locality, zip, lat, lon, formatted)
                    .withMetadata(this);
        }
        ProviderResult withRoute(String longName, String shortName) {
            return new ProviderResult(placeId, house, longName, shortName, locality, zip, lat, lon, formatted)
                    .withMetadata(this);
        }
        ProviderResult withLocality(String value) {
            return new ProviderResult(placeId, house, routeLong, routeShort, value, zip, lat, lon, formatted)
                    .withMetadata(this);
        }
        ProviderResult withCountry(String longName, String shortName) {
            ProviderResult copy = copy(); copy.countryLong = longName; copy.countryShort = shortName; return copy;
        }
        ProviderResult withState(String longName, String shortName) {
            ProviderResult copy = copy(); copy.stateLong = longName; copy.stateShort = shortName; return copy;
        }
        ProviderResult withPoint(double latitude, double longitude) {
            return new ProviderResult(placeId, house, routeLong,
                    routeShort, locality, zip, latitude, longitude, formatted).withMetadata(this);
        }
        ProviderResult withPartial(boolean value) { ProviderResult copy = copy(); copy.partial = value; return copy; }
        ProviderResult withLocationType(String value) { ProviderResult copy = copy(); copy.locationType = value; return copy; }
        ProviderResult withTypes(String... values) {
            ProviderResult copy = copy(); copy.resultTypes = new ArrayList<>(List.of(values)); return copy;
        }
        ProviderResult withFormattedAddress(String value) {
            return new ProviderResult(placeId, house, routeLong, routeShort, locality, zip, lat, lon, value)
                    .withMetadata(this);
        }
        ProviderResult withPlaceId(String value) {
            return new ProviderResult(value, house, routeLong, routeShort, locality, zip, lat, lon, formatted)
                    .withMetadata(this);
        }
        ProviderResult withExtra(Component value) { ProviderResult copy = copy(); copy.extraComponents.add(value); return copy; }
        private ProviderResult withMetadata(ProviderResult source) {
            stateLong = source.stateLong; stateShort = source.stateShort;
            countryLong = source.countryLong; countryShort = source.countryShort;
            locationType = source.locationType; partial = source.partial;
            resultTypes = new ArrayList<>(source.resultTypes);
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
                    + ",\"types\":[" + String.join(",", resultTypes.stream().map(LiteralStreetEstimateHintTest::quote).toList())
                    + "],\"formatted_address\":" + quote(formatted)
                    + ",\"address_components\":[" + String.join(",", components().stream()
                    .map(Component::json).toList()) + "],\"geometry\":{\"location_type\":"
                    + quote(locationType) + ",\"location\":{\"lat\":" + lat + ",\"lng\":" + lon + "}}}";
        }
    }
}
