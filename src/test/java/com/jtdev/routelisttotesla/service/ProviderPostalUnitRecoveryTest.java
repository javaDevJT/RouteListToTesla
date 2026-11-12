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

class ProviderPostalUnitRecoveryTest {
    private static final String INPUT = "12345 SAMPLE AVE APT 109, TESTVILLE HEIGHTS, MI";
    private static final String POINT = "42.681773,-83.1239537";
    private static final String UNIT = address("unit-rooftop", "109");
    private static final String PARENT = address("parent-rooftop", null);
    private static final String POSTAL = """
            {"types":["postal_code"],"place_id":"postal-area",
             "postcode_localities":["Testville","Testville Heights"],
             "address_components":[
              {"long_name":"48307","short_name":"48307","types":["postal_code"]},
              {"long_name":"Testville Heights","short_name":"Testville Heights","types":["locality"]},
              {"long_name":"Michigan","short_name":"MI","types":["administrative_area_level_1"]},
              {"long_name":"United States","short_name":"US","types":["country"]}],
             "geometry":{"location":{"lat":42.66789,"lng":-83.13273},"location_type":"APPROXIMATE",
              "bounds":{"northeast":{"lat":42.6974,"lng":-83.0914},"southwest":{"lat":42.6225,"lng":-83.1591}}}}
            """;

    @Test
    void preservesUnitDestinationWhenTheReversePostcodeExplicitlyContainsBothCityNames() throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
        PlaceCandidate result = resolve(INPUT, phase -> normal(phase), requests);
        assertEquals(LocationResolution.Status.VERIFIED_UNIT, result.resolution().status());
        assertEquals("unit-rooftop", result.pid());
        assertEquals(42.681773, result.lat());
        assertEquals(-83.1239537, result.lon());
        assertEquals(INPUT, result.text());
        assertEquals("corroborated_postal_unit_parent", result.resolution().strategy());
        assertEquals(4, result.resolution().attempts());
        assertEquals(java.util.Arrays.asList(INPUT, null, "12345 SAMPLE AVE UNIT 109", "12345 SAMPLE AVE"),
                requests.stream().map(r -> r.get("address")).toList());
        assertEquals(POINT, requests.get(1).get("latlng"));
        assertEquals("locality:TESTVILLE HEIGHTS|administrative_area:MI|country:US",
                requests.get(2).get("components"));
        assertEquals(requests.get(2).get("components"), requests.get(3).get("components"));
    }

    @Test
    void missingMalformedOrConflictingPostalEvidenceNeverVerifiesTheUnit() throws Exception {
        List<String> invalid = List.of(
                POSTAL.replace("\"postcode_localities\"", "\"unused\""),
                POSTAL.replace("\"Testville Heights\"]", "\"Other City\"]"),
                POSTAL.replace("[\"Testville\",\"Testville Heights\"]", "[\"Other City\",\"Testville Heights\"]"),
                POSTAL.replace("[\"Testville\",\"Testville Heights\"]", "[\"Testville\",7,\"Testville Heights\"]"),
                POSTAL.replace("[\"Testville\",\"Testville Heights\"]", "[\"Testville\",\"Testville\",\"Testville Heights\"]"),
                POSTAL.replace("\"bounds\"", "\"viewport\""),
                POSTAL.replace("42.6974", "42.65"),
                POSTAL.replace("-83.1591", "-83.10"),
                POSTAL.replace("42.6974", "\"42.6974\""),
                POSTAL.replace("42.66789", "43.66789"),
                POSTAL.replace("APPROXIMATE", "ROOFTOP"),
                POSTAL.replace("[\"Testville\",\"Testville Heights\"]", "[\"Testville\",\"\"]"),
                POSTAL.replace("[\"Testville\",\"Testville Heights\"]", "[\"Testville\",\"Testville Heights\",\"" + "X".repeat(129) + "\"]"),
                POSTAL.replace("42.6974", "42.60"),
                POSTAL.replace("42.6974", "\"NaN\""),
                POSTAL.replace("48307", "48308"),
                POSTAL.replace("\"MI\"", "\"NY\""),
                POSTAL.replace("\"US\"", "\"CA\""),
                POSTAL.replace("\"types\":[\"postal_code\"],\"place_id\"", "\"types\":[\"locality\"],\"place_id\""),
                POSTAL.replace("\"types\":[\"postal_code\"],\"place_id\"", "\"types\":[\"postal_code\",\"political\"],\"place_id\""),
                POSTAL.replace("\"place_id\":\"postal-area\"", "\"partial_match\":true,\"place_id\":\"postal-area\""));
        for (String postal : invalid) {
            held(INPUT, phase -> phase == Phase.REVERSE ? body(PARENT, postal) : normal(phase));
        }
        held(INPUT, phase -> phase == Phase.REVERSE ? body(PARENT) : normal(phase));
        held(INPUT, phase -> phase == Phase.REVERSE ? body(PARENT, POSTAL, POSTAL) : normal(phase));
        held(INPUT, phase -> phase == Phase.REVERSE
                ? body(PARENT, POSTAL, POSTAL.replace("48307", "48308")) : normal(phase));
    }

    @Test
    void forwardProofsRejectChangedUnitStreetNumberStateZipPrecisionAndRooftop() throws Exception {
        for (Phase stage : List.of(Phase.UNIT, Phase.PARENT)) {
            String original = stage == Phase.UNIT ? UNIT : PARENT;
            for (String replacement : List.of(
                    original.replace(stage == Phase.UNIT ? "unit-rooftop" : "parent-rooftop", "other-rooftop"),
                    original.replace("42.681773", "42.681873"),
                    original.replace("ROOFTOP", "RANGE_INTERPOLATED"),
                    original.replace("Sample Avenue", "Other Avenue").replace("Sample Ave", "Other Ave"),
                    original.replace("12345", "12346"),
                    original.replace("48307", "48308"),
                    original.replace("\"MI\"", "\"NY\""),
                    original.replace("\"US\"", "\"CA\""),
                    original.replace("\"place_id\":", "\"partial_match\":true,\"place_id\":"))) {
                held(INPUT, phase -> phase == stage ? body(replacement) : normal(phase));
            }
        }
        held(INPUT, phase -> phase == Phase.UNIT ? body(address("unit-rooftop", "110")) : normal(phase));
        held(INPUT, phase -> phase == Phase.UNIT ? body(PARENT) : normal(phase));
        held(INPUT, phase -> phase == Phase.PARENT ? body(address("parent-rooftop", "109")) : normal(phase));
        for (String unit : List.of("109", "110")) {
            held(INPUT, phase -> phase == Phase.PARENT
                    ? body(PARENT.replace("Sample Ave,", "Sample Ave #" + unit + ",")) : normal(phase));
        }
    }

    @Test
    void reverseProofRejectsCompetingParentsUnitsAndActualUnitEvidence() throws Exception {
        for (String parent : List.of(
                PARENT.replace("parent-rooftop", "unit-rooftop"),
                PARENT.replace("42.681773", "42.681873"),
                PARENT.replace("ROOFTOP", "RANGE_INTERPOLATED"),
                PARENT.replace("\"street_address\",\"subpremise\"", "\"subpremise\""),
                PARENT.replace("Sample Ave,", "Sample Ave #110,"),
                PARENT.replace("\"formatted_address\"", "\"unused_display\""),
                address("parent-rooftop", "110"),
                address("parent-rooftop", "109"),
                PARENT.replace("12345", "12346"),
                PARENT.replace("Sample Avenue", "Other Avenue").replace("Sample Ave", "Other Ave"),
                PARENT.replace("Testville", "Other City"),
                PARENT.replace("\"MI\"", "\"NY\""),
                PARENT.replace("\"US\"", "\"CA\""),
                PARENT.replace("48307", "48308"))) {
            held(INPUT, phase -> phase == Phase.REVERSE ? body(parent, POSTAL) : normal(phase));
        }
        for (boolean reverseOrder : List.of(false, true)) {
            String otherParent = PARENT.replace("parent-rooftop", "competing-parent");
            held(INPUT, phase -> phase == Phase.REVERSE
                    ? reverseOrder ? body(otherParent, POSTAL, PARENT) : body(PARENT, POSTAL, otherParent)
                    : normal(phase));
            held(INPUT, phase -> phase == Phase.REVERSE ? body(PARENT, POSTAL, address("different-unit", "110"))
                    : normal(phase));
            held(INPUT, phase -> phase == Phase.UNIT
                    ? reverseOrder ? body(UNIT.replace("unit-rooftop", "competing-unit"), UNIT)
                        : body(UNIT, UNIT.replace("unit-rooftop", "competing-unit")) : normal(phase));
            String otherCity = UNIT.replace("unit-rooftop", "other-city-unit").replace("Testville\",", "Testville Heights\",");
            held(INPUT, phase -> phase == Phase.UNIT ? body(UNIT, otherCity) : normal(phase));
        }
    }

    @Test
    void explicitReverseUnitNeedsNoParentAndPostalCentersNeverBecomeDestinations() throws Exception {
        PlaceCandidate result = resolve(INPUT, phase -> phase == Phase.REVERSE ? body(UNIT, POSTAL) : normal(phase), new ArrayList<>());
        assertEquals(LocationResolution.Status.VERIFIED_UNIT, result.resolution().status());
        assertEquals("unit-rooftop", result.pid());
        assertEquals(3, result.resolution().attempts());
        assertEquals(42.681773, result.lat());
        assertEquals(-83.1239537, result.lon());
        held(INPUT + " 48308", ProviderPostalUnitRecoveryTest::normal);
        held(INPUT + " 48307-9999", ProviderPostalUnitRecoveryTest::normal);
    }

    @Test
    void formattedUnitsCannotContradictStructuredUnitEvidenceInAnyUnitProof() throws Exception {
        for (String conflict : List.of("#110", "APT 110", "#109 APT 110")) {
            String contradictory = UNIT.replace("#109", conflict);
            for (Phase stage : List.of(Phase.ORIGINAL, Phase.UNIT)) {
                held(INPUT, phase -> phase == stage ? body(contradictory) : normal(phase));
            }
            held(INPUT, phase -> phase == Phase.REVERSE ? body(contradictory, POSTAL) : normal(phase));
        }
        PlaceCandidate noFormattedUnit = resolve(INPUT,
                phase -> phase == Phase.ORIGINAL || phase == Phase.UNIT ? body(UNIT.replace(" #109,", ",")) : normal(phase),
                new ArrayList<>());
        assertEquals(LocationResolution.Status.VERIFIED_UNIT, noFormattedUnit.resolution().status());
    }

    private static void held(String input, Function<Phase, String> responses) throws Exception {
        PlaceCandidate result = resolve(input, responses, new ArrayList<>());
        assertNull(result.pid());
        assertEquals(0, result.lat());
        assertEquals(0, result.lon());
        assertEquals(input, result.text());
        assertFalse(result.resolution().verified());
    }

    private static String normal(Phase phase) {
        return switch (phase) {
            case ORIGINAL, UNIT -> body(UNIT);
            case REVERSE -> body(PARENT, POSTAL);
            case PARENT -> body(PARENT);
        };
    }

    private static PlaceCandidate resolve(String input, Function<Phase, String> responses,
                                          List<Map<String, String>> requests) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/geocode", exchange -> {
            Map<String, String> query = parameters(exchange);
            requests.add(query);
            String address = query.getOrDefault("address", "");
            Phase phase = query.containsKey("latlng") ? Phase.REVERSE
                    : address.equals(input) ? Phase.ORIGINAL
                    : address.contains("UNIT") || address.contains("APT") ? Phase.UNIT : Phase.PARENT;
            byte[] bytes = responses.apply(phase).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("fixture-key",
                    "http://localhost:" + server.getAddress().getPort() + "/geocode", "us", 100, 100, 1, 1, 0);
            return client.batchGeocode("fixture-owner", List.of(new PlaceCandidate(input, input,
                    "fixture.png", 2, 0, 0, null, 3, false, List.of()))).getFirst();
        } finally { server.stop(0); }
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String body(String... results) {
        return "{\"status\":\"OK\",\"results\":[" + String.join(",", results) + "]}";
    }

    private static String address(String id, String unit) {
        return """
                {"place_id":"%s","types":["street_address","subpremise"],
                 "formatted_address":"12345 Sample Ave%s, Testville, MI 48307, USA",
                 "address_components":[%s
                  {"long_name":"12345","short_name":"12345","types":["street_number"]},
                  {"long_name":"Sample Avenue","short_name":"Sample Ave","types":["route"]},
                  {"long_name":"Testville","short_name":"Testville","types":["locality"]},
                  {"long_name":"Michigan","short_name":"MI","types":["administrative_area_level_1"]},
                  {"long_name":"United States","short_name":"US","types":["country"]},
                  {"long_name":"48307","short_name":"48307","types":["postal_code"]}],
                 "geometry":{"location":{"lat":42.681773,"lng":-83.1239537},"location_type":"ROOFTOP"}}
                """.formatted(id, unit == null ? "" : " #" + unit,
                unit == null ? "" : "{\"long_name\":\"" + unit + "\",\"short_name\":\"" + unit
                        + "\",\"types\":[\"subpremise\"]},");
    }

    private enum Phase { ORIGINAL, UNIT, REVERSE, PARENT }
}
