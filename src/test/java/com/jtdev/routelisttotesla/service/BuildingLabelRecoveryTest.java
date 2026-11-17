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
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildingLabelRecoveryTest {
    private static final String ZERO_RESULTS = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
    private static final Address BLDG = new Address(
            "8429 LAPORTE DR BLDG 24, STERLING HEIGHTS, MI");
    private static final Address BUILDING = new Address(
            "8429 LAPORTE DR BUILDING 24, STERLING HEIGHTS, MI");

    @Test
    void corroboratesExplicitBuildingLabelAgainstTheUnitlessParentRooftop() throws Exception {
        for (Address address : List.of(BLDG, BUILDING)) {
            Probe probe = resolve(address, Plan.success());
            PlaceCandidate result = probe.result();

            assertEquals("laporte-building-24", result.pid());
            assertEquals(42.5705294, result.lat(), 0.0000001);
            assertEquals(-83.0260707, result.lon(), 0.0000001);
            assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
            assertEquals("exact_building_match", result.resolution().reasonCode());
            assertEquals("corroborated_building_label", result.resolution().strategy());
            assertEquals(address.text(), result.text());
            assertEquals(address.text(), result.normalized());
            assertEquals("laporte-building.png", result.sourceImage());
            assertEquals(7, result.lineIndex());
            assertEquals(2, result.ocrAgreement());
            assertTrue(result.ocrReviewRequired());
            assertEquals(List.of("8429 LAPORTE DR BLDG 24", "8429 LAPORTE DR BUILDING 24"),
                    result.ocrAlternatives());
            assertEquals(4, probe.requests().size(), "The label recovery needs four independent provider requests");

            Map<String, String> original = probe.requests().get(0);
            assertEquals(address.text(), original.get("address"));
            assertFalse(original.containsKey("components"));
            assertFalse(original.containsKey("latlng"));

            Map<String, String> constrainedLabel = probe.requests().get(1);
            assertTrue(constrainedLabel.containsKey("components"));
            String constrainedAddress = normalize(constrainedLabel.get("address"));
            String inputLabel = address.text().contains("BUILDING 24") ? "BUILDING 24" : "BLDG 24";
            assertTrue(constrainedAddress.contains(inputLabel), constrainedAddress);
            assertFalse(constrainedAddress.contains("UNIT 24"), constrainedAddress);
            assertTrue(constrainedLabel.get("components").toUpperCase(Locale.ROOT)
                    .contains("LOCALITY:STERLING HEIGHTS"));
            assertTrue(constrainedLabel.get("components").toUpperCase(Locale.ROOT)
                    .contains("ADMINISTRATIVE_AREA:MI"));
            assertTrue(constrainedLabel.get("components").toUpperCase(Locale.ROOT).contains("COUNTRY:US"));

            Map<String, String> reverse = probe.requests().get(2);
            assertEquals("42.5705294,-83.0260707", reverse.get("latlng"));

            Map<String, String> parentForward = probe.requests().get(3);
            assertEquals("8429 LAPORTE DR", normalize(parentForward.get("address")));
            assertTrue(parentForward.containsKey("components"));
            assertFalse(parentForward.containsKey("latlng"));
            assertFalse(normalize(parentForward.get("address")).contains("24"));
        }
    }

    @Test
    void unitAndOtherSecondaryLabelsCannotSatisfyAnExplicitBuildingLabel() throws Exception {
        ProviderResult valid = ProviderResult.labelled();
        for (String input : List.of(BLDG.text(), BUILDING.text())) {
            Address address = new Address(input);
            for (ProviderResult mismatch : List.of(
                    valid.withSubpremise("UNIT 24"),
                    valid.withSubpremise("Apartment 24"),
                    valid.withSubpremise("Floor 24"),
                    valid.withSubpremise("Bldg 25"),
                    valid.withSubpremise("Bldg 024"))) {
                assertHeld(address, Plan.withLabel(mismatch));
            }
        }
        Address unitInput = new Address("8429 LAPORTE DR UNIT 24, STERLING HEIGHTS, MI");
        assertHeld(unitInput, Plan.withLabel(valid));
        Address compactUnitInput = new Address("8429 LAPORTE DR UNIT24, STERLING HEIGHTS, MI");
        assertHeld(compactUnitInput, Plan.withLabel(valid));
        Address multipleLabels = new Address("8429 LAPORTE DR BLDG 24 APT 3, STERLING HEIGHTS, MI");
        assertHeld(multipleLabels, Plan.withLabel(valid));
        for (String label : List.of("APT 24 BLDG 24", "UNIT 24 BUILDING 24", "BLDG24")) {
            assertHeld(new Address("8429 LAPORTE DR " + label + ", STERLING HEIGHTS, MI"),
                    Plan.withLabel(valid.withSubpremise("24")));
        }
    }

    @Test
    void everyForwardLabelProofMustAttestTheSameLabelIdentityAndRooftop() throws Exception {
        ProviderResult label = ProviderResult.labelled();
        for (Stage stage : List.of(Stage.ORIGINAL, Stage.LABEL_FORWARD)) {
            for (ProviderResult mismatch : List.of(
                    label.withPlaceId("another-labeled-place"),
                    label.withPoint(42.5706294, -83.0260707),
                    label.withHouse("8430"),
                    label.withRoute("Other Drive", "Other Dr"),
                    label.withLocality("Troy"),
                    label.withZip("48313"),
                    label.withSubpremise("Bldg 25"))) {
                assertHeld(BLDG, Plan.success().with(stage, List.of(mismatch)));
            }
        }
    }

    @Test
    void reverseParentAndFourthForwardProofMustAgreeOnParentIdentityAndAddressEvidence() throws Exception {
        ProviderResult parent = ProviderResult.parent();
        List<ProviderResult> invalidParents = List.of(
                parent.withPlaceId("different-parent"),
                parent.withPoint(42.5706294, -83.0260707),
                parent.withHouse("8430"),
                parent.withRoute("North Laporte Drive", "N Laporte Dr"),
                parent.withLocality("Troy"),
                parent.withZip("48313"),
                parent.withSubpremise("Apartment 24"));

        for (Stage stage : List.of(Stage.REVERSE_PARENT, Stage.PARENT_FORWARD)) {
            for (ProviderResult mismatch : invalidParents) {
                assertHeld(BLDG, Plan.success().with(stage, List.of(mismatch)));
            }
        }
    }

    @Test
    void parentMayCarryAnOverlappingSubpremiseTypeButNotAUnitComponent() throws Exception {
        ProviderResult parentWithOverlappingTypes = ProviderResult.parent()
                .withTypes("street_address", "subpremise");
        Probe accepted = resolve(BLDG, Plan.success()
                .with(Stage.REVERSE_PARENT, List.of(parentWithOverlappingTypes))
                .with(Stage.PARENT_FORWARD, List.of(parentWithOverlappingTypes)));
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, accepted.result().resolution().status());
        assertEquals("laporte-building-24", accepted.result().pid());

        ProviderResult unitComponent = ProviderResult.parent().withSubpremise("Bldg 24");
        assertHeld(BLDG, Plan.success().with(Stage.REVERSE_PARENT, List.of(unitComponent)));
        assertHeld(BLDG, Plan.success().with(Stage.PARENT_FORWARD, List.of(unitComponent)));
    }

    @Test
    void buildingIdentityPreservesHyphensInsteadOfCollapsingDifferentLabels() throws Exception {
        Address address = new Address("8429 LAPORTE DR BLDG A-1, STERLING HEIGHTS, MI");
        Probe exact = resolve(address, Plan.withLabel(ProviderResult.labelled().withSubpremise("Building A-1")));
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, exact.result().resolution().status());
        assertEquals("exact_building_match", exact.result().resolution().reasonCode());
        assertHeld(address, Plan.withLabel(ProviderResult.labelled().withSubpremise("Building A1")));
    }

    @Test
    void sameLabeledRooftopReverseCanCorroborateWithoutSubstitutingAParent() throws Exception {
        Probe probe = resolve(BLDG, Plan.success().with(Stage.REVERSE_PARENT, List.of(ProviderResult.labelled())));
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, probe.result().resolution().status());
        assertEquals("laporte-building-24", probe.result().pid());
        assertEquals(3, probe.requests().size());
    }

    @Test
    void competingLabelOrParentIdentitiesKeepTheDestinationHeldInEitherOrder() throws Exception {
        ProviderResult label = ProviderResult.labelled();
        ProviderResult otherLabel = label.withPlaceId("competing-building").withPoint(42.5705295, -83.0260707);
        ProviderResult parent = ProviderResult.parent();
        ProviderResult otherParent = parent.withPlaceId("competing-parent").withPoint(42.5705295, -83.0260707);

        for (List<ProviderResult> order : List.of(List.of(label, otherLabel), List.of(otherLabel, label))) {
            assertHeld(BLDG, Plan.success().with(Stage.ORIGINAL, order));
            assertHeld(BLDG, Plan.success().with(Stage.LABEL_FORWARD, order));
        }
        for (List<ProviderResult> order : List.of(List.of(parent, otherParent), List.of(otherParent, parent))) {
            assertHeld(BLDG, Plan.success().with(Stage.REVERSE_PARENT, order));
            assertHeld(BLDG, Plan.success().with(Stage.PARENT_FORWARD, order));
        }
    }

    private static void assertHeld(Address address, Plan plan) throws Exception {
        PlaceCandidate result = resolve(address, plan).result();
        assertNull(result.pid(), "Unproved building labels must not become navigation destinations");
        assertEquals(0, result.lat());
        assertEquals(0, result.lon());
        assertNotNull(result.resolution());
        assertNotEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        assertNotEquals(LocationResolution.Status.VERIFIED_UNIT, result.resolution().status());
    }

    private static Probe resolve(Address address, Plan plan) throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            Map<String, String> query = parameters(exchange);
            requests.add(query);
            Stage stage = stage(query);
            List<ProviderResult> values = plan.results(stage);
            respond(exchange, body(values));
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("fixture-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", "us",
                    100, 100, 1, 1, 0);
            PlaceCandidate input = new PlaceCandidate(address.text(), address.text(), "laporte-building.png",
                    7, 0, 0, null, 2, true,
                    List.of("8429 LAPORTE DR BLDG 24", "8429 LAPORTE DR BUILDING 24"));
            PlaceCandidate result = client.batchGeocode("building-label-fixture", List.of(input)).getFirst();
            return new Probe(result, List.copyOf(requests));
        } finally {
            server.stop(0);
        }
    }

    private static Stage stage(Map<String, String> query) {
        if (query.containsKey("latlng")) return Stage.REVERSE_PARENT;
        if (!query.containsKey("components")) return Stage.ORIGINAL;
        return hasBuildingLabel(query.getOrDefault("address", ""))
                ? Stage.LABEL_FORWARD : Stage.PARENT_FORWARD;
    }

    private static boolean hasBuildingLabel(String address) {
        String value = normalize(address);
        return value.matches(".*\\b(?:BLDG|BUILDING)\\s+[A-Z0-9-]+\\b.*");
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            for (String item : query.split("&")) {
                String[] parts = item.split("=", 2);
                params.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(parts.length == 2 ? parts[1] : "", StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static String body(List<ProviderResult> results) {
        if (results.isEmpty()) return ZERO_RESULTS;
        return "{\"status\":\"OK\",\"results\":["
                + String.join(",", results.stream().map(ProviderResult::json).toList()) + "]}";
    }

    private static void respond(HttpExchange exchange, String response) throws java.io.IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private enum Stage { ORIGINAL, LABEL_FORWARD, REVERSE_PARENT, PARENT_FORWARD }

    private record Address(String text) { }
    private record Probe(PlaceCandidate result, List<Map<String, String>> requests) { }

    private record Plan(List<ProviderResult> original, List<ProviderResult> labelForward,
                        List<ProviderResult> reverseParent, List<ProviderResult> parentForward) {
        static Plan success() {
            return new Plan(List.of(ProviderResult.labelled()), List.of(ProviderResult.labelled()),
                    List.of(ProviderResult.parent()), List.of(ProviderResult.parent()));
        }

        static Plan withLabel(ProviderResult label) {
            return success().with(Stage.ORIGINAL, List.of(label)).with(Stage.LABEL_FORWARD, List.of(label));
        }

        Plan with(Stage stage, List<ProviderResult> replacement) {
            return switch (stage) {
                case ORIGINAL -> new Plan(replacement, labelForward, reverseParent, parentForward);
                case LABEL_FORWARD -> new Plan(original, replacement, reverseParent, parentForward);
                case REVERSE_PARENT -> new Plan(original, labelForward, replacement, parentForward);
                case PARENT_FORWARD -> new Plan(original, labelForward, reverseParent, replacement);
            };
        }

        List<ProviderResult> results(Stage stage) {
            return switch (stage) {
                case ORIGINAL -> original;
                case LABEL_FORWARD -> labelForward;
                case REVERSE_PARENT -> reverseParent;
                case PARENT_FORWARD -> parentForward;
            };
        }
    }

    private static final class ProviderResult {
        private final String placeId;
        private final String house;
        private final String routeLong;
        private final String routeShort;
        private final String locality;
        private final String zip;
        private final double latitude;
        private final double longitude;
        private final String subpremise;
        private final List<String> types;

        private ProviderResult(String placeId, String house, String routeLong, String routeShort,
                               String locality, String zip, double latitude, double longitude,
                               String subpremise, List<String> types) {
            this.placeId = placeId;
            this.house = house;
            this.routeLong = routeLong;
            this.routeShort = routeShort;
            this.locality = locality;
            this.zip = zip;
            this.latitude = latitude;
            this.longitude = longitude;
            this.subpremise = subpremise;
            this.types = List.copyOf(types);
        }

        static ProviderResult labelled() {
            return new ProviderResult("laporte-building-24", "8429", "Laporte Drive", "Laporte Dr",
                    "Sterling Heights", "48312", 42.5705294, -83.0260707, "bldg 24",
                    List.of("street_address", "subpremise"));
        }

        static ProviderResult parent() {
            return new ProviderResult("laporte-parent", "8429", "Laporte Drive", "Laporte Dr",
                    "Sterling Heights", "48312", 42.5705294, -83.0260707, null,
                    List.of("street_address", "premise"));
        }

        private ProviderResult copy(String id, String newHouse, String newRouteLong, String newRouteShort,
                                    String newLocality, String newZip, double lat, double lon,
                                    String newSubpremise, List<String> newTypes) {
            return new ProviderResult(id, newHouse, newRouteLong, newRouteShort, newLocality, newZip,
                    lat, lon, newSubpremise, newTypes);
        }

        ProviderResult withPlaceId(String value) {
            return copy(value, house, routeLong, routeShort, locality, zip, latitude, longitude, subpremise, types);
        }
        ProviderResult withHouse(String value) {
            return copy(placeId, value, routeLong, routeShort, locality, zip, latitude, longitude, subpremise, types);
        }
        ProviderResult withRoute(String longName, String shortName) {
            return copy(placeId, house, longName, shortName, locality, zip, latitude, longitude, subpremise, types);
        }
        ProviderResult withLocality(String value) {
            return copy(placeId, house, routeLong, routeShort, value, zip, latitude, longitude, subpremise, types);
        }
        ProviderResult withZip(String value) {
            return copy(placeId, house, routeLong, routeShort, locality, value, latitude, longitude, subpremise, types);
        }
        ProviderResult withPoint(double lat, double lon) {
            return copy(placeId, house, routeLong, routeShort, locality, zip, lat, lon, subpremise, types);
        }
        ProviderResult withSubpremise(String value) {
            return copy(placeId, house, routeLong, routeShort, locality, zip, latitude, longitude, value, types);
        }
        ProviderResult withTypes(String... values) {
            return copy(placeId, house, routeLong, routeShort, locality, zip, latitude, longitude,
                    subpremise, List.of(values));
        }

        String json() {
            List<String> components = new ArrayList<>();
            components.add(component(house, house, "street_number"));
            components.add(component(routeLong, routeShort, "route"));
            if (subpremise != null) components.add(component(subpremise, subpremise, "subpremise"));
            components.add(component(locality, locality, "locality"));
            components.add(component("Michigan", "MI", "administrative_area_level_1"));
            components.add(component("United States", "US", "country"));
            components.add(component(zip, zip, "postal_code"));
            String display = house + " " + routeShort + (subpremise == null ? "" : " " + subpremise)
                    + ", " + locality + ", MI " + zip + ", USA";
            return "{\"place_id\":" + quote(placeId) + ",\"partial_match\":false,\"types\":["
                    + String.join(",", types.stream().map(BuildingLabelRecoveryTest::quote).toList())
                    + "],\"formatted_address\":" + quote(display) + ",\"address_components\":["
                    + String.join(",", components) + "],\"geometry\":{\"location_type\":\"ROOFTOP\","
                    + "\"location\":{\"lat\":" + latitude + ",\"lng\":" + longitude + "}}}";
        }

        private static String component(String longName, String shortName, String type) {
            return "{\"long_name\":" + quote(longName) + ",\"short_name\":" + quote(shortName)
                    + ",\"types\":[" + quote(type) + "]}";
        }
    }
}
