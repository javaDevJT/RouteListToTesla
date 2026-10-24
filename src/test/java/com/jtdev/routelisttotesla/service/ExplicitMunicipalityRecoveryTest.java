package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ExplicitMunicipalityRecoveryTest {
    private static final Address GATEWAY = new Address(
            "3156 GATEWAY LEDGE, COMMERCE TOWNSHIP, MI", "3156", "GATEWAY LEDGE",
            "COMMERCE TOWNSHIP", "MI", "Michigan", "Oakland County", "United States", "US",
            "48390", null, "ChIJTVMFVfikJIgR6s8icoaTA1U", 42.56783739999999, -83.461818);

    private static final Address DUBLIN = new Address(
            "150 OAK RIDGE RD, TOWNSHIP OF DUBLIN, OH", "150", "OAK RIDGE RD",
            "TOWNSHIP OF DUBLIN", "OH", "Ohio", "Franklin County", "United States", "US",
            "43016", null, "dublin-township-roof", 40.0985, -83.1537);

    @Test
    void recoversExactGatewayLedgeEvidenceThroughAllThreeProofs() throws Exception {
        ProviderResult rooftop = gatewayRooftop();
        Probe probe = resolve(GATEWAY, phase -> switch (phase) {
            case ORIGINAL, STRUCTURED -> List.of(rooftop);
            case REVERSE -> gatewayReverseResults();
        });

        assertAccepted(probe, GATEWAY);
        assertEquals(Integer.valueOf(11), probe.resultCounts().get(Phase.REVERSE),
                "Keep all eleven real reverse results so unrelated, interpolated, and approximate results remain exercised");
    }

    @Test
    void recognizesASecondExplicitTownshipFormNearAnOhioPostalAnchorAndAllowsNeighborhood() throws Exception {
        ProviderResult rooftop = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp")
                .withExtra(new Component("Muirfield Village", "Muirfield Village", "neighborhood"));
        Probe probe = resolve(DUBLIN, ignored -> List.of(rooftop));

        assertAccepted(probe, DUBLIN);
    }

    @Test
    void townshipDesignationMustBeAWholeWord() throws Exception {
        Address address = address("75 MAIN ST, NEW TOWNSHIPVILLE, NJ", "75", "MAIN ST",
                "NEW TOWNSHIPVILLE", "NJ", "New Jersey", "Test County", "08901", null,
                "newtownshipville-roof", 40.5, -74.4);
        ProviderResult result = roof(address, "Main Street", "Main St",
                "Newville Township", "Newville Twp");

        assertUnresolved(resolve(address, ignored -> List.of(result)));
    }

    @Test
    void sublocalityOrPostalTownCannotMasqueradeAsAnExplicitMunicipality() throws Exception {
        for (String type : List.of("postal_town", "sublocality", "sublocality_level_1", "sublocality_level_2")) {
            ProviderResult candidate = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                    "Dublin Charter Township", "Dublin Twp")
                    .withoutType("administrative_area_level_3")
                    .withExtra(new Component(DUBLIN.city(), DUBLIN.city(), type));
            assertUnresolved(resolve(DUBLIN, ignored -> List.of(candidate)));
        }
    }

    @Test
    void anActualPrimaryCityWithTheExactFullLabelRetainsThreeStageValidation() throws Exception {
        ProviderResult candidate = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp")
                .withoutType("administrative_area_level_3")
                .withExtra(new Component(DUBLIN.city(), DUBLIN.city(), "locality"));
        assertAccepted(resolve(DUBLIN, ignored -> List.of(candidate)), DUBLIN);
    }

    @Test
    void theProviderLongNameMustRetainTheTownshipDesignation() throws Exception {
        ProviderResult candidate = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd", "Dublin", "Dublin Twp");
        assertUnresolved(resolve(DUBLIN, ignored -> List.of(candidate)));
    }

    @Test
    void bareMunicipalityNameStillNeedsExistingPostalCityProof() throws Exception {
        Address bare = address("3156 GATEWAY LEDGE, COMMERCE, MI", "3156", "GATEWAY LEDGE",
                "COMMERCE", "MI", "Michigan", "Oakland County", "48390", null,
                "commerce-bare-roof", GATEWAY.lat(), GATEWAY.lon());
        ProviderResult result = roof(bare, "Gateway Ledge", "Gateway Ledge",
                "Commerce Township", "Commerce Township");

        assertUnresolved(resolve(bare, ignored -> List.of(result)));
    }

    @Test
    void requiresOneNonblankMunicipalityComponentAndDoesNotUseCountyAlone() throws Exception {
        ProviderResult valid = gatewayRooftop();
        List<ProviderResult> invalid = List.of(
                valid.withoutType("administrative_area_level_3"),
                valid.withType("administrative_area_level_3",
                        new Component("", "", "administrative_area_level_3")),
                valid.withExtra(new Component("Commerce Township", "Commerce Township",
                        "administrative_area_level_3")),
                valid.withoutType("administrative_area_level_3")
                        .withType("administrative_area_level_2",
                                new Component("Commerce Township", "Commerce Township",
                                        "administrative_area_level_2")));

        for (ProviderResult candidate : invalid) {
            assertUnresolved(resolve(GATEWAY, ignored -> List.of(candidate)));
        }
    }

    @Test
    void explicitTownshipWithASuffixedStreetStillRequiresAUsablePostalZip() throws Exception {
        Address missingZip = address(DUBLIN.text(), DUBLIN.house(), DUBLIN.route(), DUBLIN.city(), DUBLIN.state(),
                DUBLIN.stateLong(), DUBLIN.county(), null, null, DUBLIN.placeId(), DUBLIN.lat(), DUBLIN.lon());
        ProviderResult rooftop = roof(missingZip, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp");

        assertUnresolved(resolve(missingZip, ignored -> List.of(rooftop)));
    }

    @Test
    void rejectsConflictingMunicipalityLongAndShortNames() throws Exception {
        ProviderResult result = gatewayRooftop().withType("administrative_area_level_3",
                new Component("Commerce Township", "Wolverine Lake Township",
                        "administrative_area_level_3"));

        assertUnresolved(resolve(GATEWAY, ignored -> List.of(result)));
    }

    @Test
    void rejectsAHigherPriorityLocalityThatConflictsWithTheTownship() throws Exception {
        ProviderResult township = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp");
        ProviderResult candidate = township.withExtra(
                new Component("Wolverine Lake", "Wolverine Lake", "locality"));
        assertUnresolved(resolve(DUBLIN, ignored -> List.of(candidate)));
    }

    @Test
    void rejectsAConflictingLevelTwoSublocalityAlongsideTheExplicitTownship() throws Exception {
        ProviderResult township = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp");
        ProviderResult candidate = township.withExtra(
                new Component("Wolverine Lake", "Wolverine Lake", "sublocality_level_2"));

        assertUnresolved(resolve(DUBLIN, ignored -> List.of(candidate)));
    }

    @Test
    void rejectsAProviderTownshipWithADifferentMunicipalStem() throws Exception {
        ProviderResult result = gatewayRooftop().withType("administrative_area_level_3",
                new Component("Wolverine Lake Township", "Wolverine Lake Twp",
                        "administrative_area_level_3"));

        assertUnresolved(resolve(GATEWAY, ignored -> List.of(result)));
    }

    @Test
    void requiresTheExpectedStateAndUnitedStatesCountry() throws Exception {
        ProviderResult valid = gatewayRooftop();
        ProviderResult wrongState = valid.withType("administrative_area_level_1",
                new Component("Ohio", "OH", "administrative_area_level_1"));
        ProviderResult wrongCountry = valid.withType("country",
                new Component("Canada", "CA", "country"));

        assertUnresolved(resolve(GATEWAY, ignored -> List.of(wrongState)));
        assertUnresolved(resolve(GATEWAY, ignored -> List.of(wrongCountry)));
    }

    @Test
    void requiresAZipWithANearbyCatalogAnchorForTheReturnedState() throws Exception {
        ProviderResult unsupportedZip = roof(DUBLIN, "Oak Ridge Road", "Oak Ridge Rd",
                "Dublin Charter Township", "Dublin Twp").withType("postal_code",
                new Component("99999", "99999", "postal_code"));

        assertUnresolved(resolve(DUBLIN, ignored -> List.of(unsupportedZip)));
    }

    @Test
    void requiresTheSamePostalCodeAcrossAllThreeAttestations() throws Exception {
        ProviderResult valid = gatewayRooftop();
        Probe probe = resolve(GATEWAY, phase -> List.of(
                phase == Phase.STRUCTURED ? valid.withType("postal_code",
                        new Component("48391", "48391", "postal_code")) : valid));

        assertUnresolved(probe);
    }

    @Test
    void rejectsAConflictingApartmentNumber() throws Exception {
        Address apartment = address("3156 GATEWAY LEDGE APT 4, COMMERCE TOWNSHIP, MI", "3156",
                "GATEWAY LEDGE APT 4", "COMMERCE TOWNSHIP", "MI", "Michigan",
                "Oakland County", "48390", "4", "commerce-apartment-roof",
                GATEWAY.lat(), GATEWAY.lon());
        ProviderResult result = roof(apartment, "Gateway Ledge", "Gateway Ledge",
                "Commerce Township", "Commerce Township")
                .withType("subpremise", new Component("5", "5", "subpremise"));

        assertUnresolved(resolve(apartment, ignored -> List.of(result)));
    }

    @Test
    void aCompetingRooftopForTheSameHouseRemainsAmbiguous() throws Exception {
        ProviderResult proposed = gatewayRooftop();
        ProviderResult competitor = proposed.withPlaceId("different-gateway-rooftop")
                .withPoint(42.56791, -83.46180);
        Probe probe = resolve(GATEWAY, phase -> phase == Phase.STRUCTURED
                ? List.of(proposed, competitor) : List.of(proposed));

        assertUnresolved(probe);
        assertEquals(LocationResolution.Status.AMBIGUOUS, probe.result().resolution().status());
    }

    @Test
    void constrainedProofMustKeepTheProposedPlaceIdAndRooftopPoint() throws Exception {
        ProviderResult valid = gatewayRooftop();
        List<ProviderResult> changed = List.of(
                valid.withPlaceId("different-gateway-place-id"),
                valid.withPoint(42.56790, -83.461818));

        for (ProviderResult candidate : changed) {
            Probe probe = resolve(GATEWAY, phase -> phase == Phase.STRUCTURED
                    ? List.of(candidate) : List.of(valid));
            assertUnresolved(probe);
        }
    }

    @Test
    void interpolatedResultsCannotReplaceTheConstrainedOrReverseRooftopProof() throws Exception {
        ProviderResult valid = gatewayRooftop();
        for (Phase proofPhase : List.of(Phase.STRUCTURED, Phase.REVERSE)) {
            Probe probe = resolve(GATEWAY, phase -> List.of(phase == proofPhase
                    ? valid.withLocationType("RANGE_INTERPOLATED") : valid));
            assertUnresolved(probe);
        }
    }

    private static void assertAccepted(Probe probe, Address address) {
        PlaceCandidate result = probe.result();
        assertEquals(address.placeId(), result.pid());
        assertEquals(address.text(), result.text());
        assertEquals(address.lat(), result.lat(), 0.0000001);
        assertEquals(address.lon(), result.lon(), 0.0000001);
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        assertEquals("corroborated_street_structure", result.resolution().strategy());
        assertEquals(GeocodingClient.GEOCODING_VERSION, result.resolution().policyVersion());
        assertEquals(3, probe.requests().size(), "Original, unchanged constrained street, then reverse proof");

        Map<String, String> original = probe.requests().get(0);
        Map<String, String> constrained = probe.requests().get(1);
        Map<String, String> reverse = probe.requests().get(2);
        assertEquals(address.text(), original.get("address"));
        assertFalse(original.containsKey("components"));
        assertEquals(address.house() + " " + address.route(), constrained.get("address"));
        assertTrue(constrained.get("components").contains("locality:" + address.city()));
        assertTrue(constrained.get("components").contains("administrative_area:" + address.state()));
        assertTrue(constrained.get("components").contains("country:US"));
        String[] point = reverse.get("latlng").split(",", 2);
        assertEquals(address.lat(), Double.parseDouble(point[0]), 0.0000001);
        assertEquals(address.lon(), Double.parseDouble(point[1]), 0.0000001);
    }

    private static void assertUnresolved(Probe probe) {
        assertNull(probe.result().pid());
        assertNotNull(probe.result().resolution());
        assertFalse(probe.result().resolution().verified());
    }

    private static ProviderResult gatewayRooftop() {
        return roof(GATEWAY, "Gateway Ledge", "Gateway Ledge",
                "Commerce Township", "Commerce Township").withZipSuffix("4301");
    }

    private static List<ProviderResult> gatewayReverseResults() {
        ProviderResult first = gatewayRooftop();
        ProviderResult otherHouse = roof(GATEWAY, "Gateway Ledge", "Gateway Ledge",
                "Commerce Township", "Commerce Township")
                .withHouse("3135")
                .withPlaceId("ChIJA3oZ-PikJIgRXo2qSLb5k3g")
                .withPoint(42.5676889, -83.4623246)
                .withZipSuffix(null);
        ProviderResult interpolation = gatewayRooftop()
                .withTypes("street_address")
                .withPlaceId("EjQzMTU2IEdhdGV3YXkgTGVkZ2UsIENvbW1lcmNlIFRvd25zaGlwLCBNSSA0ODM5MCwgVVNBIhsSGQoUChIJ2w8QavikJIgRV_eLm4E5WY4Q1Bg")
                .withPoint(42.567849, -83.4619104)
                .withLocationType("RANGE_INTERPOLATED")
                .withZipSuffix(null);
        ProviderResult localityCenter = sparseResult(
                "Egs4NkpSSEc5USs0NyImOiQKCg0mVl8ZFby8QM4QChoUChIJ06nBCralJIgRugH_HPRmzm0",
                List.of("plus_code"),
                List.of(
                        new Component("HG9Q+47", "HG9Q+47", "plus_code"),
                        new Component("Wolverine Lake", "Wolverine Lake", "locality"),
                        new Component("Commerce Township", "Commerce Township", "administrative_area_level_3"),
                        new Component("Oakland County", "Oakland County", "administrative_area_level_2"),
                        new Component("Michigan", "MI", "administrative_area_level_1"),
                        new Component("United States", "US", "country"),
                        new Component("48390", "48390", "postal_code")),
                "GEOMETRIC_CENTER", 42.56783739999999, -83.461818);
        ProviderResult pointOfInterest = roof(GATEWAY, "Blue Stone Lane", "Blue Stone Ln",
                "Commerce Township", "Commerce Township")
                .withHouse("1905")
                .withPlaceId("ChIJmRqkkfikJIgR33JEhMBcg58")
                .withPoint(42.5670196, -83.46356519999999)
                .withZipSuffix("4306")
                .withTypes("establishment", "health", "point_of_interest");
        ProviderResult routeRange = roof(GATEWAY, "Gateway Ledge", "Gateway Ledge",
                "Commerce Township", "Commerce Township")
                .withHouse("3048-3282")
                .withPlaceId("ChIJ2w8QavikJIgRV_eLm4E5WY4")
                .withPoint(42.56821002052499, -83.46228564383988)
                .withLocationType("GEOMETRIC_CENTER")
                .withTypes("route")
                .withZipSuffix(null);
        ProviderResult postalCode = sparseResult(
                "ChIJ8YaF0aSlJIgR0WGbme-obgk",
                List.of("postal_code"),
                townshipComponents("Commerce Township", "48390"),
                "APPROXIMATE", 42.5513866, -83.4750241);
        ProviderResult township = sparseResult(
                "ChIJ7WkomIOmJIgRTiG-urxkIk0",
                List.of("administrative_area_level_3", "political"),
                List.of(
                        new Component("Commerce Township", "Commerce Township", "administrative_area_level_3"),
                        new Component("Oakland County", "Oakland County", "administrative_area_level_2"),
                        new Component("Michigan", "MI", "administrative_area_level_1"),
                        new Component("United States", "US", "country")),
                "APPROXIMATE", 42.5695563, -83.4794976);
        ProviderResult county = sparseResult(
                "ChIJ2S-UKKSNJIgRO9LSPL-n88Y",
                List.of("administrative_area_level_2", "political"),
                List.of(
                        new Component("Oakland County", "Oakland County", "administrative_area_level_2"),
                        new Component("Michigan", "MI", "administrative_area_level_1"),
                        new Component("United States", "US", "country")),
                "APPROXIMATE", 42.59219239999999, -83.33618799999999);
        ProviderResult state = sparseResult(
                "ChIJEQTKxz2qTE0Rs8liellI3Zc",
                List.of("administrative_area_level_1", "political"),
                List.of(
                        new Component("Michigan", "MI", "administrative_area_level_1"),
                        new Component("United States", "US", "country")),
                "APPROXIMATE", 44.3148443, -85.60236429999999);
        ProviderResult country = sparseResult(
                "ChIJCzYy5IS16lQRQrfeQ5K5Oxw",
                List.of("country", "political"),
                List.of(new Component("United States", "US", "country")),
                "APPROXIMATE", 38.7945952, -106.5348379);
        return List.of(first, otherHouse, interpolation, localityCenter, pointOfInterest,
                routeRange, postalCode, township, county, state, country);
    }

    private static List<Component> townshipComponents(String township, String zip) {
        return List.of(
                new Component(township, township, "administrative_area_level_3"),
                new Component("Oakland County", "Oakland County", "administrative_area_level_2"),
                new Component("Michigan", "MI", "administrative_area_level_1"),
                new Component("United States", "US", "country"),
                new Component(zip, zip, "postal_code"));
    }

    private static ProviderResult roof(Address address, String routeLong, String routeShort,
                                       String townshipLong, String townshipShort) {
        List<Component> components = new ArrayList<>();
        components.add(new Component(address.house(), address.house(), "street_number"));
        components.add(new Component(routeLong, routeShort, "route"));
        if (townshipLong != null || townshipShort != null) {
            components.add(new Component(townshipLong, townshipShort, "administrative_area_level_3"));
        }
        if (address.county() != null) {
            components.add(new Component(address.county(), address.county(), "administrative_area_level_2"));
        }
        components.add(new Component(address.stateLong(), address.state(), "administrative_area_level_1"));
        components.add(new Component(address.countryLong(), address.countryShort(), "country"));
        if (address.zip() != null) components.add(new Component(address.zip(), address.zip(), "postal_code"));
        if (address.unit() != null) {
            components.add(new Component(address.unit(), address.unit(), "subpremise"));
        }
        return new ProviderResult(address.placeId(), List.of("premise", "street_address"), components,
                "ROOFTOP", address.lat(), address.lon(), false);
    }

    private static ProviderResult sparseResult(String id, List<String> types, List<Component> components,
                                               String locationType, double lat, double lon) {
        return new ProviderResult(id, types, components, locationType, lat, lon, false);
    }

    private static Address address(String text, String house, String route, String city, String state,
                                   String stateLong, String county, String zip, String unit, String placeId,
                                   double lat, double lon) {
        return new Address(text, house, route, city, state, stateLong, county,
                "United States", "US", zip, unit, placeId, lat, lon);
    }

    private static Probe resolve(Address address, Function<Phase, List<ProviderResult>> response) throws Exception {
        List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
        Map<Phase, Integer> resultCounts = new HashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            Map<String, String> params = parameters(exchange);
            requests.add(params);
            Phase phase = params.containsKey("latlng") ? Phase.REVERSE
                    : params.containsKey("components") ? Phase.STRUCTURED : Phase.ORIGINAL;
            List<ProviderResult> results = response.apply(phase);
            synchronized (resultCounts) {
                resultCounts.put(phase, results.size());
            }
            respond(exchange, body(results));
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("test-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode",
                    "us", 100, 100, 1, 1, 0);
            PlaceCandidate candidate = new PlaceCandidate(address.text(), address.text(), "synthetic.png",
                    0, 0, 0, null);
            PlaceCandidate result = client.batchGeocode("owner", List.of(candidate)).get(0);
            synchronized (resultCounts) {
                return new Probe(result, List.copyOf(requests), Map.copyOf(resultCounts));
            }
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> parameters(HttpExchange exchange) {
        Map<String, String> params = new HashMap<>();
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

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String body(List<ProviderResult> results) {
        return "{\"status\":\"OK\",\"results\":["
                + String.join(",", results.stream().map(ProviderResult::json).toList()) + "]}";
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private record Address(String text, String house, String route, String city, String state,
                           String stateLong, String county, String countryLong, String countryShort,
                           String zip, String unit, String placeId, double lat, double lon) { }

    private record Component(String longName, String shortName, String type) {
        String json() {
            return "{\"long_name\":" + quote(longName) + ",\"short_name\":" + quote(shortName)
                    + ",\"types\":[" + quote(type) + "]}";
        }
    }

    private record ProviderResult(String placeId, List<String> resultTypes, List<Component> components,
                                  String locationType, double lat, double lon, boolean partial) {
        ProviderResult {
            resultTypes = List.copyOf(resultTypes);
            components = List.copyOf(components);
        }

        ProviderResult withPlaceId(String value) {
            return new ProviderResult(value, resultTypes, components, locationType, lat, lon, partial);
        }

        ProviderResult withPoint(double latitude, double longitude) {
            return new ProviderResult(placeId, resultTypes, components, locationType, latitude, longitude, partial);
        }

        ProviderResult withLocationType(String value) {
            return new ProviderResult(placeId, resultTypes, components, value, lat, lon, partial);
        }

        ProviderResult withTypes(String... values) {
            return new ProviderResult(placeId, List.of(values), components, locationType, lat, lon, partial);
        }

        ProviderResult withHouse(String value) {
            return withType("street_number", new Component(value, value, "street_number"));
        }

        ProviderResult withZipSuffix(String value) {
            return value == null ? withoutType("postal_code_suffix")
                    : withType("postal_code_suffix", new Component(value, value, "postal_code_suffix"));
        }

        ProviderResult withExtra(Component extra) {
            List<Component> changed = new ArrayList<>(components);
            changed.add(extra);
            return new ProviderResult(placeId, resultTypes, changed, locationType, lat, lon, partial);
        }

        ProviderResult withType(String type, Component replacement) {
            List<Component> changed = new ArrayList<>();
            for (Component component : components) if (!component.type().equals(type)) changed.add(component);
            changed.add(replacement);
            return new ProviderResult(placeId, resultTypes, changed, locationType, lat, lon, partial);
        }

        ProviderResult withoutType(String type) {
            List<Component> changed = components.stream().filter(component -> !component.type().equals(type)).toList();
            return new ProviderResult(placeId, resultTypes, changed, locationType, lat, lon, partial);
        }

        String json() {
            String componentsJson = String.join(",", components.stream().map(Component::json).toList());
            String typesJson = String.join(",", resultTypes.stream().map(ExplicitMunicipalityRecoveryTest::quote).toList());
            return "{\"place_id\":" + quote(placeId) + ",\"partial_match\":" + partial
                    + ",\"types\":[" + typesJson + "],\"address_components\":[" + componentsJson
                    + "],\"geometry\":{\"location_type\":" + quote(locationType)
                    + ",\"location\":{\"lat\":" + lat + ",\"lng\":" + lon + "}}}";
        }
    }

    private enum Phase { ORIGINAL, STRUCTURED, REVERSE }
    private record Probe(PlaceCandidate result, List<Map<String, String>> requests,
                         Map<Phase, Integer> resultCounts) { }
}
