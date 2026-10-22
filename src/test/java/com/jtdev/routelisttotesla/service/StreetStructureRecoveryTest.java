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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class StreetStructureRecoveryTest {
    private static final String EMPTY = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";
    private static final Address COTTONWOOD = new Address(
            "6734 COTTONWOOD KNL, WEST BLOOMFIELD, MI", "6734", "COTTONWOOD KNL",
            "WEST BLOOMFIELD", "MI", null,
            new ProviderResult("ChIJAWFmeju6JIgROxGN70o291Y", "6734", "Cottonwood Knoll Court",
                    "Cottonwood Knoll Ct", null, "West Bloomfield Township", "48322",
                    42.5398147, -83.3490782));
    private static final Address SILVER_POND = new Address(
            "5537 SILVER POND, WEST BLOOMFIELD, MI", "5537", "SILVER POND",
            "WEST BLOOMFIELD", "MI", null,
            new ProviderResult("ChIJzbAKre-6JIgR8yTrh1X4EXs", "5537", "Silver Pond", "Silver Pond",
                    null, "West Bloomfield Township", "48322", 42.5443302, -83.3917224));
    private static final Address BROADWAY = new Address("100 BROADWAY, NEW YORK, NY", "100", "BROADWAY",
            "NEW YORK", "NY", null,
            new ProviderResult("broadway-ny", "100", "Broadway", "Broadway", "New York", null,
                    "10007", 40.7131, -74.0072).withState("New York", "NY"));
    private static final Address MISSING_SUFFIX = new Address("50 MAPLE, TESTVILLE, MI", "50", "MAPLE",
            "TESTVILLE", "MI", null,
            new ProviderResult("maple-testville", "50", "Maple Street", "Maple St", "Testville", null,
                    "48000", 42.3, -83.2));
    private static final Address NORTH_MAIN = new Address("100 N MAIN ST, TESTVILLE, MI", "100", "N MAIN ST",
            "TESTVILLE", "MI", null,
            new ProviderResult("north-main", "100", "North Main Street", "N Main St", "Testville", null,
                    "48000", 42.31, -83.2));
    private static final Address KNOWN_SUFFIX = new Address("100 MAIN ST, TESTVILLE, MI", "100", "MAIN ST",
            "TESTVILLE", "MI", null,
            new ProviderResult("known-main", "100", "Main Street", "Main St", "Testville", null,
                    "48000", 42.32, -83.2));

    @Test
    void recoversCottonwoodKnollAliasFromThreeProviderAttestations() throws Exception {
        assertRecovered(COTTONWOOD);
    }

    @Test
    void recoversLiteralSuffixlessSilverPondFromThreeProviderAttestations() throws Exception {
        assertRecovered(SILVER_POND);
    }

    @Test
    void acceptsOtherCitiesSuffixlessNamesAndConservedNameAndDirectionTokens() throws Exception {
        assertRecovered(BROADWAY);
        assertRecovered(MISSING_SUFFIX);
        assertVerified(NORTH_MAIN);
    }

    @Test
    void verifiesTheRequestedUnitOnlyWhenAllStructureProofsAgree() throws Exception {
        Address apartment = new Address("100 MAIN APT 4, TESTVILLE, MI", "100", "MAIN",
                "TESTVILLE", "MI", "4",
                new ProviderResult("unit-main", "100", "Main Street", "Main St", "Testville", null,
                        "48000", 42.33, -83.2).withExtra(new Component("4", "4", "subpremise")));
        assertRecovered(apartment, LocationResolution.Status.VERIFIED_UNIT);
    }

    @Test
    void knownSuffixDirectMatchKeepsTheExistingSingleLookupPath() throws Exception {
        Probe probe = probe(KNOWN_SUFFIX, phase -> body(KNOWN_SUFFIX.provider()));

        assertEquals(KNOWN_SUFFIX.provider().placeId, probe.result().pid());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, probe.result().resolution().status());
        assertEquals(KNOWN_SUFFIX.text(), probe.result().text());
        assertEquals(1, probe.requests().size());
        assertFalse(probe.requests().getFirst().containsKey("latlng"));
    }

    @Test
    void rejectsWrongHouseAddedNameOrDirectionTokensAndInconsistentProviderNames() throws Exception {
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider().withHouse("6735")));
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider()
                .withRoute("Cottonwood East Knoll Court", "Cottonwood East Knoll Ct")));
        assertRejected(NORTH_MAIN.withProvider(NORTH_MAIN.provider()
                .withRoute("South Main Street", "S Main St")));
        assertRejected(SILVER_POND.withProvider(SILVER_POND.provider()
                .withRoute("Silver Pond", "Silver Ponds")));
    }

    @Test
    void rejectsPartialCityCenterAndNonRooftopProviderResults() throws Exception {
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider().withPartial(true)));
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider()
                .withTypes("locality").withoutType("street_number").withoutType("route")
                .withLocationType("APPROXIMATE")));
        assertRejected(SILVER_POND.withProvider(SILVER_POND.provider()
                .withLocationType("RANGE_INTERPOLATED")));
    }

    @Test
    void rejectsDuplicateComponentsContradictoryCityAndPostalCode() throws Exception {
        assertRejected(SILVER_POND.withProvider(SILVER_POND.provider()
                .withExtra(new Component("Silver Pond", "Silver Pond", "route"))));
        assertRejected(SILVER_POND.withProvider(SILVER_POND.provider()
                .withExtra(new Component("48322", "48322", "postal_code"))));
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider().withLocality("Pontiac")
                .withExtra(new Component("West Bloomfield", "West Bloomfield", "locality"))));
        Address typedZip = new Address("6734 COTTONWOOD KNL, WEST BLOOMFIELD, MI 48322", "6734",
                "COTTONWOOD KNL", "WEST BLOOMFIELD", "MI", null,
                COTTONWOOD.provider().withZip("48323"));
        assertRejected(typedZip);
    }

    @Test
    void eachProofMustBePresentAndAttestTheSameRooftop() throws Exception {
        assertRejected(COTTONWOOD, phase -> phase == Phase.STRUCTURED
                ? "{\"status\":\"REQUEST_DENIED\",\"results\":[]}" : body(COTTONWOOD.provider()));
        for (Phase phase : List.of(Phase.STRUCTURED, Phase.REVERSE)) {
            assertRejected(COTTONWOOD, actual -> body(actual == phase
                    ? COTTONWOOD.provider().withPlaceId("different-rooftop") : COTTONWOOD.provider()));
            assertRejected(COTTONWOOD, actual -> body(actual == phase
                    ? COTTONWOOD.provider().withPoint(42.5399147, -83.3490782) : COTTONWOOD.provider()));
            assertRejected(COTTONWOOD, actual -> body(actual == phase
                    ? COTTONWOOD.provider().withLocationType("RANGE_INTERPOLATED") : COTTONWOOD.provider()));
        }
        assertRejected(COTTONWOOD, actual -> actual == Phase.REVERSE
                ? EMPTY : body(COTTONWOOD.provider()));
    }

    @Test
    void aCatalogWordInsideTheStreetNameStillNeedsAllThreeProofsBeforeAddingItsTerminalSuffix() throws Exception {
        Address completed = new Address("100 MAIN ST, TESTVILLE, MI", "100", "MAIN ST", "TESTVILLE", "MI", null,
                new ProviderResult("completed-main", "100", "Main Street Court", "Main Street Ct", "Testville", null,
                        "48000", 42.32, -83.2));
        assertRecovered(completed);
        assertRejected(completed, phase -> body(phase == Phase.STRUCTURED
                ? completed.provider().withRoute("Main Street", "Main St").withPlaceId("actual-main-street")
                : completed.provider()));
    }

    @Test
    void duplicateStateAndCountryComponentsCannotEstablishTheNewProof() throws Exception {
        for (Phase phase : Phase.values()) {
            for (Component duplicate : List.of(new Component("Michigan", "MI", "administrative_area_level_1"),
                    new Component("United States", "US", "country"))) {
                assertRejected(SILVER_POND, actual -> body(actual == phase
                        ? SILVER_POND.provider().withExtra(duplicate) : SILVER_POND.provider()));
            }
        }
    }

    @Test
    void contradictoryLongAndShortNamesCannotVerifyAnOrdinarySuffixedStreet() throws Exception {
        assertRejected(KNOWN_SUFFIX.withProvider(KNOWN_SUFFIX.provider().withRoute("Main Street", "Different St")));
    }

    @Test
    void catalogAbbreviationsInsideProviderNamesPreservePreviouslyValidDirectMatches() throws Exception {
        for (String supplied : List.of("GOLFVIEW DRIVE BLVD", "GOLFVIEW DR BLVD")) {
            Address address = new Address("100 " + supplied + ", TESTVILLE, MI", "100", supplied, "TESTVILLE", "MI", null,
                    new ProviderResult("provider-name-alias", "100", "Golfview Drive Boulevard", "Golfview Dr Blvd",
                            "Testville", null, "48000", 42.32, -83.2));
            Probe probe = probe(address, phase -> body(address.provider()));
            assertEquals("provider-name-alias", probe.result().pid());
            assertEquals(address.text(), probe.result().text());
            assertEquals(1, probe.requests().size(), "Provider abbreviation consistency must retain the direct path");
        }
    }

    @Test
    void everyDirectPlaceIdentityCountsWhenMixedWithARecoverableStructureInEitherOrder() throws Exception {
        ProviderResult directA = KNOWN_SUFFIX.provider().withPlaceId("completed-main");
        ProviderResult directB = directA.withPlaceId("different-main");
        ProviderResult completion = directA.withRoute("Main Street Court", "Main Street Ct");
        for (List<ProviderResult> order : List.of(List.of(directA, directB, completion), List.of(directB, directA, completion))) {
            assertRejected(KNOWN_SUFFIX, phase -> body(order));
        }
    }

    @Test
    void townshipOnlyStructureProofRequiresCatalogSupportedZipEvenWithoutSuppliedZip() throws Exception {
        assertRejected(SILVER_POND.withProvider(SILVER_POND.provider().withZip("48321")));
        assertRejected(COTTONWOOD.withProvider(COTTONWOOD.provider().withZip("48321")));
    }

    @Test
    void blankProviderRouteNamesRemainUnresolvedWithoutThrowing() throws Exception {
        for (Phase phase : Phase.values()) {
            assertRejected(SILVER_POND, actual -> body(actual == phase
                    ? SILVER_POND.provider().withRoute("", "Silver Pond") : SILVER_POND.provider()));
        }
    }

    @Test
    void eachStructureProofMustRetainTheProposedZipEvenWhenBothZipsSupportThePostalCity() throws Exception {
        for (Phase phase : List.of(Phase.STRUCTURED, Phase.REVERSE)) {
            assertRejected(SILVER_POND, actual -> body(actual == phase
                    ? SILVER_POND.provider().withZip("48323") : SILVER_POND.provider()));
        }
    }

    @Test
    void competingPlausibleDestinationsInAnyProofRemainUnresolved() throws Exception {
        ProviderResult competitor = COTTONWOOD.provider().withPlaceId("competing-rooftop")
                .withPoint(42.5399, -83.3490);
        for (Phase phase : Phase.values()) {
            assertRejected(COTTONWOOD, actual -> actual == phase
                    ? body(List.of(COTTONWOOD.provider(), competitor)) : body(COTTONWOOD.provider()));
        }
    }

    @Test
    void conflictingApartmentNeverVerifiesTheBuildingOrUnit() throws Exception {
        Address apartment = new Address("100 MAIN ST APT 4, TESTVILLE, MI", "100", "MAIN ST",
                "TESTVILLE", "MI", "4",
                new ProviderResult("unit-main", "100", "Main Street", "Main St", "Testville", null,
                        "48000", 42.33, -83.2).withExtra(new Component("5", "5", "subpremise")));
        assertRejected(apartment);
    }

    private static void assertRecovered(Address address) throws Exception {
        assertRecovered(address, LocationResolution.Status.VERIFIED_BUILDING);
    }

    private static void assertRecovered(Address address, LocationResolution.Status expectedStatus) throws Exception {
        Probe probe = probe(address, phase -> body(address.provider()));
        PlaceCandidate result = probe.result();

        assertEquals(address.provider().placeId, result.pid());
        assertEquals(address.text(), result.text(), "Keep the OCR text unchanged");
        assertEquals(expectedStatus, result.resolution().status());
        assertNotNull(result.resolution().matchedAddress());
        assertTrue(probe.requests().size() <= 8, "The shared per-stop request ceiling is eight");

        int original = indexOf(probe.requests(), query -> !query.containsKey("components")
                && !query.containsKey("latlng")
                && normalize(query.getOrDefault("address", "")).equals(normalize(address.text())));
        int constrained = indexOf(probe.requests(), query -> query.containsKey("components")
                && normalize(query.getOrDefault("address", "")).equals(normalize(address.proofAddress())));
        int reverse = indexOf(probe.requests(), query -> query.containsKey("latlng"));
        assertTrue(original >= 0, "The original OCR address is the first provider query");
        assertTrue(constrained > original, "The unchanged supplied street follows the original query");
        assertTrue(constrained >= 0, "The unchanged supplied street needs a locality-constrained proof");
        assertTrue(reverse > constrained, "The same rooftop must then be confirmed by reverse geocoding");
        String components = probe.requests().get(constrained).get("components").toUpperCase(Locale.ROOT);
        assertTrue(components.contains("LOCALITY:" + address.city()), components);
        assertTrue(components.contains("ADMINISTRATIVE_AREA:" + address.state()), components);
        assertTrue(components.contains("COUNTRY:US"), components);

        String[] point = probe.requests().get(reverse).get("latlng").split(",", 2);
        assertEquals(address.provider().lat, Double.parseDouble(point[0]), 0.0000001);
        assertEquals(address.provider().lon, Double.parseDouble(point[1]), 0.0000001);
    }

    private static void assertVerified(Address address) throws Exception {
        Probe probe = probe(address, phase -> body(address.provider()));
        assertEquals(address.provider().placeId, probe.result().pid());
        assertEquals(address.text(), probe.result().text());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, probe.result().resolution().status());
        assertTrue(probe.requests().size() <= 8);
    }

    private static void assertRejected(Address address) throws Exception {
        assertRejected(address, phase -> body(address.provider()));
    }

    private static void assertRejected(Address address, Function<Phase, String> response) throws Exception {
        Probe probe = probe(address, response);
        assertNull(probe.result().pid(), "An unproved or ambiguous street structure must not be navigable");
    }

    private static int indexOf(List<Map<String, String>> values, Function<Map<String, String>, Boolean> predicate) {
        for (int index = 0; index < values.size(); index++) if (predicate.apply(values.get(index))) return index;
        return -1;
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static Probe probe(Address address, Function<Phase, String> response) throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
        HttpServer server = server(exchange -> {
            Map<String, String> params = parameters(exchange);
            requests.add(params);
            Phase phase = params.containsKey("latlng") ? Phase.REVERSE
                    : params.containsKey("components") ? Phase.STRUCTURED : Phase.ORIGINAL;
            respond(exchange, response.apply(phase));
        });
        server.start();
        try {
            PlaceCandidate result = client(server).batchGeocode("owner", List.of(place(address.text()))).getFirst();
            return new Probe(result, List.copyOf(requests));
        } finally {
            server.stop(0);
        }
    }

    private static GeocodingClient client(HttpServer server) {
        return new GeocodingClient("test-key", "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode",
                "us", 100, 100, 1, 1, 0);
    }

    private static PlaceCandidate place(String text) {
        return new PlaceCandidate(text, text, "synthetic.png", 0, 0, 0, null);
    }

    private interface Handler { void handle(HttpExchange exchange) throws Exception; }

    private static HttpServer server(Handler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            try { handler.handle(exchange); }
            catch (Exception e) { exchange.close(); }
        });
        return server;
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

    private static String body(ProviderResult... results) { return body(List.of(results)); }

    private static String body(List<ProviderResult> results) {
        return "{\"status\":\"OK\",\"results\":[" + String.join(",", results.stream()
                .map(ProviderResult::json).toList()) + "]}";
    }

    private enum Phase { ORIGINAL, STRUCTURED, REVERSE }
    private record Probe(PlaceCandidate result, List<Map<String, String>> requests) { }
    private record Address(String text, String house, String street, String city, String state, String unit,
                           ProviderResult provider) {
        String proofAddress() { return house + " " + street + (unit == null ? "" : " UNIT " + unit); }
        Address withProvider(ProviderResult replacement) {
            return new Address(text, house, street, city, state, unit, replacement);
        }
    }
    private record Component(String longName, String shortName, String type) {
        String json() {
            return "{\"long_name\":" + quote(longName) + ",\"short_name\":" + quote(shortName)
                    + ",\"types\":[" + quote(type) + "]}";
        }
    }

    private static final class ProviderResult {
        private String placeId;
        private String house;
        private String routeLong;
        private String routeShort;
        private String locality;
        private String municipality;
        private String zip;
        private String stateLong = "Michigan";
        private String stateShort = "MI";
        private double lat;
        private double lon;
        private boolean partial;
        private String locationType = "ROOFTOP";
        private List<String> resultTypes = new ArrayList<>(List.of("street_address"));
        private final List<Component> extraComponents = new ArrayList<>();
        private final List<String> omittedTypes = new ArrayList<>();

        ProviderResult(String placeId, String house, String routeLong, String routeShort, String locality,
                       String municipality, String zip, double lat, double lon) {
            this.placeId = placeId;
            this.house = house;
            this.routeLong = routeLong;
            this.routeShort = routeShort;
            this.locality = locality;
            this.municipality = municipality;
            this.zip = zip;
            this.lat = lat;
            this.lon = lon;
        }

        ProviderResult copy() {
            ProviderResult copy = new ProviderResult(placeId, house, routeLong, routeShort, locality, municipality,
                    zip, lat, lon);
            copy.partial = partial;
            copy.locationType = locationType;
            copy.stateLong = stateLong;
            copy.stateShort = stateShort;
            copy.resultTypes = new ArrayList<>(resultTypes);
            copy.extraComponents.addAll(extraComponents);
            copy.omittedTypes.addAll(omittedTypes);
            return copy;
        }

        ProviderResult withHouse(String value) { ProviderResult copy = copy(); copy.house = value; return copy; }
        ProviderResult withRoute(String longName, String shortName) {
            ProviderResult copy = copy(); copy.routeLong = longName; copy.routeShort = shortName; return copy;
        }
        ProviderResult withLocality(String value) { ProviderResult copy = copy(); copy.locality = value; return copy; }
        ProviderResult withZip(String value) { ProviderResult copy = copy(); copy.zip = value; return copy; }
        ProviderResult withPoint(double latitude, double longitude) {
            ProviderResult copy = copy(); copy.lat = latitude; copy.lon = longitude; return copy;
        }
        ProviderResult withPlaceId(String value) { ProviderResult copy = copy(); copy.placeId = value; return copy; }
        ProviderResult withPartial(boolean value) { ProviderResult copy = copy(); copy.partial = value; return copy; }
        ProviderResult withLocationType(String value) { ProviderResult copy = copy(); copy.locationType = value; return copy; }
        ProviderResult withTypes(String... values) {
            ProviderResult copy = copy(); copy.resultTypes = new ArrayList<>(List.of(values)); return copy;
        }
        ProviderResult withExtra(Component component) { ProviderResult copy = copy(); copy.extraComponents.add(component); return copy; }
        ProviderResult withState(String longName, String shortName) {
            ProviderResult copy = copy(); copy.stateLong = longName; copy.stateShort = shortName; return copy;
        }
        ProviderResult withoutType(String type) {
            ProviderResult copy = copy(); copy.omittedTypes.add(type); return copy;
        }

        private List<Component> components() {
            List<Component> components = new ArrayList<>();
            if (house != null) components.add(new Component(house, house, "street_number"));
            if (routeLong != null) components.add(new Component(routeLong, routeShort, "route"));
            if (locality != null) components.add(new Component(locality, locality, "locality"));
            if (municipality != null) components.add(new Component(municipality, municipality, "administrative_area_level_3"));
            components.add(new Component(stateLong, stateShort, "administrative_area_level_1"));
            components.add(new Component("United States", "US", "country"));
            if (zip != null) components.add(new Component(zip, zip, "postal_code"));
            components.addAll(extraComponents);
            components.removeIf(component -> omittedTypes.contains(component.type()));
            return components;
        }

        private String json() {
            String formattedCity = locality == null ? municipality : locality;
            String formatted = (house == null ? "" : house + " ") + (routeLong == null ? "" : routeLong)
                    + (formattedCity == null ? "" : ", " + formattedCity)
                    + (zip == null ? "" : " " + zip) + ", " + stateShort;
            return "{\"place_id\":" + quote(placeId) + ",\"partial_match\":" + partial
                    + ",\"types\":[" + String.join(",", resultTypes.stream().map(StreetStructureRecoveryTest::quote).toList())
                    + "],\"formatted_address\":" + quote(formatted) + ",\"address_components\":["
                    + String.join(",", components().stream().map(Component::json).toList())
                    + "],\"geometry\":{\"location_type\":" + quote(locationType)
                    + ",\"location\":{\"lat\":" + lat + ",\"lng\":" + lon + "}}}";
        }
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
