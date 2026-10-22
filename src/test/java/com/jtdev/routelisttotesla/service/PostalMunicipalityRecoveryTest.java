package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PostalMunicipalityRecoveryTest {
    private record Sample(String city, String state, String zip, double lat, double lon,
                          String house, String route, String neighborhood, String municipality,
                          String county, String placeId) { }

    private record Component(String longName, String shortName, String type) { }

    // The approved forward and reverse Google results for this address share this exact
    // rooftop, place ID, ZIP, and Waterford Township component. Their neighborhood is
    // Elizabeth Lake, so it cannot act as the requested postal city.
    private static final Sample WATERFORD = new Sample("WATERFORD", "MI", "48327",
            42.6412346, -83.3867018, "241", "Reymont Road", "Elizabeth Lake",
            "Waterford Township", "Oakland County", "ChIJn9dpVAS9JIgRIByJPQ3qmqs");

    // Canton is a distinct postal-city association in the bundled catalog. Its township
    // name differs from the postal city, exercising the same rule beyond Waterford.
    private static final Sample CANTON = new Sample("CANTON", "MI", "48187",
            42.332, -83.4695, "100", "Canton Center Road", "Cherry Hill",
            "Canton Charter Township", "Wayne County", "canton-roof");

    @Test
    void reportedWaterfordAddressUsesCorroboratedPostalLocalityAndPreservesInput() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        AtomicInteger calls = new AtomicInteger();

        PlaceCandidate result = resolve(input, body(WATERFORD, standardComponents(WATERFORD)),
                body(WATERFORD, standardComponents(WATERFORD)), calls);

        assertEquals(WATERFORD.placeId(), result.pid());
        assertEquals(WATERFORD.lat(), result.lat());
        assertEquals(WATERFORD.lon(), result.lon());
        assertEquals(input, result.text());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, result.resolution().status());
        assertEquals("corroborated_postal_locality", result.resolution().strategy());
        assertEquals("street-address-v10", result.resolution().policyVersion());
        assertEquals(2, calls.get(), "The exact reverse rooftop result must be reused as the second request");
    }

    @Test
    void catalogBacksASecondPostalCityWithADifferentTownshipName() throws Exception {
        String input = "100 CANTON CENTER RD, CANTON, MI";
        AtomicInteger calls = new AtomicInteger();

        PlaceCandidate result = resolve(input, body(CANTON, standardComponents(CANTON)),
                body(CANTON, standardComponents(CANTON)), calls);

        assertEquals(CANTON.placeId(), result.pid());
        assertEquals(CANTON.lat(), result.lat());
        assertEquals(CANTON.lon(), result.lon());
        assertEquals(input, result.text());
        assertEquals("corroborated_postal_locality", result.resolution().strategy());
        assertEquals(2, calls.get());
    }

    @Test
    void equivalentTownshipLongAndShortNamesAreAcceptedAcrossBothCatalogCases() throws Exception {
        List<Sample> samples = List.of(WATERFORD, WATERFORD, WATERFORD, CANTON);
        List<String> inputs = List.of("241 REYMONT RD, WATERFORD, MI", "241 REYMONT RD, WATERFORD, MI",
                "241 REYMONT RD, WATERFORD, MI", "100 CANTON CENTER RD, CANTON, MI");
        List<Component> waterford = standardComponents(WATERFORD);
        List<Component> canton = standardComponents(CANTON);
        List<List<Component>> variants = List.of(
                replaceType(waterford, "administrative_area_level_3",
                        component("Waterford Township", "Waterford Twp", "administrative_area_level_3")),
                replaceType(waterford, "administrative_area_level_3",
                        component("Waterford Township", "Waterford", "administrative_area_level_3")),
                replaceType(waterford, "administrative_area_level_3",
                        component("Charter Township of Waterford", "Waterford", "administrative_area_level_3")),
                replaceType(canton, "administrative_area_level_3",
                        component("Canton Charter Township", "Canton", "administrative_area_level_3")));

        for (int index = 0; index < variants.size(); index++) {
            Sample sample = samples.get(index);
            String response = body(sample, variants.get(index));
            PlaceCandidate result = resolve(inputs.get(index), response, response, new AtomicInteger());

            assertEquals(sample.placeId(), result.pid(), response);
            assertEquals(inputs.get(index), result.text());
            assertEquals("corroborated_postal_locality", result.resolution().strategy());
        }
    }

    @Test
    void neighborhoodCountyOrStateCannotReplaceMissingPostalCityAndTownship() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> full = standardComponents(WATERFORD);
        List<List<Component>> withoutTownship = List.of(
                withoutType(full, "administrative_area_level_3"),
                List.of(component("Oakland County", "administrative_area_level_2"),
                        component("Michigan", "MI", "administrative_area_level_1"),
                        component("United States", "US", "country"),
                        component(WATERFORD.zip(), "postal_code")));

        for (List<Component> components : withoutTownship) {
            PlaceCandidate result = resolve(input, body(WATERFORD, components), body(WATERFORD, components),
                    new AtomicInteger());
            assertNull(result.pid(), components.toString());
        }
    }

    @Test
    void fallbackRequiresOneConsistentTownshipAndAValidMatchingZip() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> base = standardComponents(WATERFORD);

        List<Component> noZip = withoutType(base, "postal_code");
        List<Component> wrongZip = replaceType(base, "postal_code", component("48035", "postal_code"));
        List<Component> duplicateZip = append(base, component("48327", "postal_code"));
        List<Component> duplicateTownship = append(base,
                component("Waterford Township", "administrative_area_level_3"));
        List<Component> conflictingTownship = append(base,
                component("Another Township", "administrative_area_level_3"));
        List<Component> blankTownship = replaceType(base, "administrative_area_level_3",
                component("", "", "administrative_area_level_3"));
        List<Component> inconsistentTownshipNames = replaceType(base, "administrative_area_level_3",
                component("Waterford Township", "Different Township", "administrative_area_level_3"));

        for (List<Component> components : List.of(noZip, wrongZip, duplicateZip, duplicateTownship,
                conflictingTownship, blankTownship, inconsistentTownshipNames)) {
            PlaceCandidate result = resolve(input, body(WATERFORD, components), body(WATERFORD, components),
                    new AtomicInteger());
            assertNull(result.pid(), components.toString());
        }
    }

    @Test
    void explicitOrMalformedHigherPriorityLocalityCannotFallThroughToTownship() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> fallbackOnly = standardComponents(WATERFORD);
        List<Component> explicitOtherCity = append(fallbackOnly, component("Other City", "locality"));
        List<Component> malformedPrimaryCity = append(append(fallbackOnly,
                component("Other City", "locality")), component("Yet Another City", "locality"));
        List<Component> blankPrimaryCity = append(fallbackOnly, component("", "", "locality"));
        String reverse = body(WATERFORD, fallbackOnly);

        for (List<Component> components : List.of(explicitOtherCity, malformedPrimaryCity, blankPrimaryCity)) {
            PlaceCandidate result = resolve(input, body(WATERFORD, components), reverse, new AtomicInteger());
            assertNull(result.pid(), components.toString());
        }
    }

    @Test
    void competingMunicipalRooftopAtPostalAddressIsAmbiguousRegardlessOfOrder() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> goodComponents = standardComponents(WATERFORD);
        String forward = body(WATERFORD, goodComponents);
        String goodReverse = body(WATERFORD, goodComponents);
        List<Component> otherMunicipality = replaceType(goodComponents, "administrative_area_level_3",
                component("Another Township", "administrative_area_level_3"));
        otherMunicipality = replaceType(otherMunicipality, "neighborhood", component("Other Neighborhood", "neighborhood"));
        String competingRoof = body(WATERFORD, otherMunicipality, "competing-roof", WATERFORD.lat() + 0.00003,
                WATERFORD.lon(), "ROOFTOP", null, WATERFORD.route());

        for (String reverse : List.of(combine(goodReverse, competingRoof), combine(competingRoof, goodReverse))) {
            PlaceCandidate result = resolve(input, forward, reverse, new AtomicInteger());
            assertNull(result.pid(), reverse);
            assertEquals(LocationResolution.Status.AMBIGUOUS, result.resolution().status(), reverse);
        }
    }

    @Test
    void equivalentAdministrativeThreeLabelsCanVaryBetweenForwardAndReverseProofs() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> base = standardComponents(WATERFORD);
        List<List<Component>> forwardVariants = List.of(
                replaceType(base, "administrative_area_level_3",
                        component("Waterford Township", "Waterford", "administrative_area_level_3")),
                replaceType(base, "administrative_area_level_3",
                        component("Waterford Township", "Waterford Township", "administrative_area_level_3")));
        List<List<Component>> reverseVariants = List.of(
                replaceType(base, "administrative_area_level_3",
                        component("Waterford Twp", "Waterford Township", "administrative_area_level_3")),
                replaceType(base, "administrative_area_level_3",
                        component("Charter Township of Waterford", "Waterford", "administrative_area_level_3")));

        for (int index = 0; index < forwardVariants.size(); index++) {
            PlaceCandidate result = resolve(input, body(WATERFORD, forwardVariants.get(index)),
                    body(WATERFORD, reverseVariants.get(index)), new AtomicInteger());
            assertEquals(WATERFORD.placeId(), result.pid());
            assertEquals(input, result.text());
            assertEquals("corroborated_postal_locality", result.resolution().strategy());
        }
    }

    @Test
    void municipalityFallbackRequiresTheSameTypedMunicipalityAcrossProofs() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        List<Component> forwardComponents = standardComponents(WATERFORD);
        String forward = body(WATERFORD, forwardComponents);

        List<Component> changedMunicipality = replaceType(forwardComponents, "administrative_area_level_3",
                component("Another Township", "administrative_area_level_3"));
        changedMunicipality = replaceType(changedMunicipality, "neighborhood", component("Other Neighborhood", "neighborhood"));
        PlaceCandidate municipalityChange = resolve(input, forward, body(WATERFORD, changedMunicipality), new AtomicInteger());
        assertNull(municipalityChange.pid());

        List<Component> changedType = replaceType(forwardComponents, "administrative_area_level_3",
                component("Waterford Township", "locality"));
        PlaceCandidate semanticTypeChange = resolve(input, forward, body(WATERFORD, changedType), new AtomicInteger());
        assertNull(semanticTypeChange.pid());
    }

    @Test
    void samePlaceDifferentMunicipalityCannotHideAConflictingApartment() throws Exception {
        String input = "241 REYMONT RD APT 330, WATERFORD, MI";
        List<Component> goodComponents = standardComponents(WATERFORD);
        String withUnit = body(WATERFORD, goodComponents, WATERFORD.placeId(), WATERFORD.lat(),
                WATERFORD.lon(), "ROOFTOP", "330", WATERFORD.route());

        List<Component> otherMunicipality = replaceType(goodComponents, "administrative_area_level_3",
                component("Another Township", "administrative_area_level_3"));
        otherMunicipality = replaceType(otherMunicipality, "neighborhood", component("Other Neighborhood", "neighborhood"));
        String conflictingUnit = body(WATERFORD, otherMunicipality, WATERFORD.placeId(), WATERFORD.lat(),
                WATERFORD.lon(), "ROOFTOP", "331", WATERFORD.route());

        for (String reverse : List.of(combine(withUnit, conflictingUnit), combine(conflictingUnit, withUnit))) {
            PlaceCandidate result = resolve(input, withUnit, reverse, new AtomicInteger());
            assertNull(result.pid(), reverse);
            assertEquals("unit_conflict", result.resolution().reasonCode(), reverse);
        }
    }

    @Test
    void reverseProofRequiresMatchingMunicipalitySameRooftopAndRooftopPrecision() throws Exception {
        String input = "241 REYMONT RD, WATERFORD, MI";
        String forward = body(WATERFORD, standardComponents(WATERFORD));
        List<String> invalidReverseResults = List.of(
                body(WATERFORD, replaceType(standardComponents(WATERFORD), "administrative_area_level_3",
                        component("Another Township", "administrative_area_level_3"))),
                body(WATERFORD, standardComponents(WATERFORD), "different-place", WATERFORD.lat(),
                        WATERFORD.lon(), "ROOFTOP", null, WATERFORD.route()),
                body(WATERFORD, standardComponents(WATERFORD), WATERFORD.placeId(), WATERFORD.lat() + 0.00003,
                        WATERFORD.lon(), "ROOFTOP", null, WATERFORD.route()),
                body(WATERFORD, standardComponents(WATERFORD), WATERFORD.placeId(), WATERFORD.lat(),
                        WATERFORD.lon(), "RANGE_INTERPOLATED", null, WATERFORD.route()));

        for (String reverse : invalidReverseResults) {
            PlaceCandidate result = resolve(input, forward, reverse, new AtomicInteger());
            assertNull(result.pid(), reverse);
        }
    }

    @Test
    void municipalityFallbackDoesNotRelaxStreetUnitOrCompetingRooftopChecks() throws Exception {
        String plainInput = "241 REYMONT RD, WATERFORD, MI";
        String good = body(WATERFORD, standardComponents(WATERFORD));
        String wrongStreet = body(WATERFORD, standardComponents(WATERFORD), WATERFORD.placeId(),
                WATERFORD.lat(), WATERFORD.lon(), "ROOFTOP", null, "Other Road");
        assertNull(resolve(plainInput, wrongStreet, wrongStreet, new AtomicInteger()).pid());

        String unitInput = "241 REYMONT RD APT 330, WATERFORD, MI";
        String withUnit = body(WATERFORD, standardComponents(WATERFORD), WATERFORD.placeId(), WATERFORD.lat(),
                WATERFORD.lon(), "ROOFTOP", "330", WATERFORD.route());
        PlaceCandidate verifiedUnit = resolve(unitInput, withUnit, withUnit, new AtomicInteger());
        assertEquals(LocationResolution.Status.VERIFIED_UNIT, verifiedUnit.resolution().status());
        assertEquals("corroborated_postal_locality", verifiedUnit.resolution().strategy());

        String wrongUnit = body(WATERFORD, standardComponents(WATERFORD), WATERFORD.placeId(), WATERFORD.lat(),
                WATERFORD.lon(), "ROOFTOP", "331", WATERFORD.route());
        PlaceCandidate unitConflict = resolve(unitInput, withUnit, wrongUnit, new AtomicInteger());
        assertNull(unitConflict.pid());
        assertEquals("unit_conflict", unitConflict.resolution().reasonCode());

        String missingUnit = body(WATERFORD, standardComponents(WATERFORD));
        PlaceCandidate buildingOnly = resolve(unitInput, withUnit, missingUnit, new AtomicInteger());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, buildingOnly.resolution().status());
        assertEquals("unit_not_confirmed", buildingOnly.resolution().reasonCode());

        String competitor = body(WATERFORD, standardComponents(WATERFORD), "another-roof",
                WATERFORD.lat() + 0.00003, WATERFORD.lon(), "ROOFTOP", null, WATERFORD.route());
        PlaceCandidate ambiguous = resolve(plainInput, good, combine(good, competitor), new AtomicInteger());
        assertNull(ambiguous.pid());
        assertEquals(LocationResolution.Status.AMBIGUOUS, ambiguous.resolution().status());
    }

    private static List<Component> standardComponents(Sample sample) {
        return List.of(component(sample.neighborhood(), "neighborhood"),
                component(sample.municipality(), "administrative_area_level_3"),
                component(sample.county(), "administrative_area_level_2"),
                component("Michigan", sample.state(), "administrative_area_level_1"),
                component("United States", "US", "country"),
                component(sample.zip(), "postal_code"));
    }

    private static List<Component> append(List<Component> components, Component additional) {
        List<Component> copy = new ArrayList<>(components);
        copy.add(additional);
        return copy;
    }

    private static List<Component> withoutType(List<Component> components, String type) {
        return components.stream().filter(component -> !component.type().equals(type)).toList();
    }

    private static List<Component> replaceType(List<Component> components, String type, Component replacement) {
        List<Component> copy = new ArrayList<>(withoutType(components, type));
        copy.add(replacement);
        return copy;
    }

    private static PlaceCandidate resolve(String text, String forward, String reverse, AtomicInteger calls)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            calls.incrementAndGet();
            String response = exchange.getRequestURI().getRawQuery().contains("latlng=") ? reverse : forward;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("fixture-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", "us",
                    1000, 2000, 2, 4, 0);
            return client.batchGeocode("owner", List.of(new PlaceCandidate(text, text, "fixture.png", 0,
                    0, 0, null, 3, false, List.of()))).getFirst();
        } finally {
            server.stop(0);
        }
    }

    private static String body(Sample sample, List<Component> components) {
        return body(sample, components, sample.placeId(), sample.lat(), sample.lon(), "ROOFTOP", null,
                sample.route());
    }

    private static String body(Sample sample, List<Component> components, String placeId, double lat,
                               double lon, String locationType, String unit, String route) {
        StringBuilder json = new StringBuilder("{\"status\":\"OK\",\"results\":[{\"place_id\":\"")
                .append(placeId).append("\",\"partial_match\":false,\"formatted_address\":\"")
                .append(sample.house()).append(' ').append(route).append(", ").append(sample.municipality())
                .append(", MI ").append(sample.zip()).append(", USA\",\"types\":[\"premise\",\"street_address\"],")
                .append("\"address_components\":[")
                .append(componentJson(component(sample.house(), "street_number"))).append(',')
                .append(componentJson(component(route, "route")));
        for (Component component : components) json.append(',').append(componentJson(component));
        if (unit != null) json.append(',').append(componentJson(component(unit, "subpremise")));
        return json.append("],\"geometry\":{\"location_type\":\"").append(locationType)
                .append("\",\"location\":{\"lat\":").append(lat).append(",\"lng\":").append(lon)
                .append("}}}]}").toString();
    }

    private static Component component(String name, String type) {
        return new Component(name, name, type);
    }

    private static Component component(String longName, String shortName, String type) {
        return new Component(longName, shortName, type);
    }

    private static String componentJson(Component component) {
        return "{\"long_name\":\"" + component.longName() + "\",\"short_name\":\""
                + component.shortName() + "\",\"types\":[\"" + component.type() + "\"]}";
    }

    private static String combine(String first, String second) {
        return first.substring(0, first.length() - 2) + "," + second.substring(second.indexOf("[{" ) + 1);
    }
}
