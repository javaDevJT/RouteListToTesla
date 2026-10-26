package com.jtdev.routelisttotesla.service;

import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PostalLocalityRecoveryTest {
    private record Sample(String city, String state, String zip, double lat, double lon) { }
    private static final Sample LOCAL = new Sample("GROSSE POINTE", "MI", "48230", 42.3742093, -82.9321866);

    @Test
    void postalEvidenceAndExactReverseRooftopRecoverDifferentMunicipalNames() throws Exception {
        for (Sample sample : List.of(LOCAL,
                new Sample("BOSTON", "MA", "02108", 42.357, -71.0637),
                new Sample("NEW YORK", "NY", "10001", 40.7506, -73.9972),
                new Sample("BEVERLY HILLS", "CA", "90210", 34.0901, -118.4065))) {
            for (String unit : new String[]{null, "330", "A208"}) {
                String original = body(sample, "Sample Road", "MAP MUNICIPALITY", unit);
                String text = "12345 SAMPLE RD" + (unit == null ? "" : " APT " + unit)
                        + ", " + sample.city() + ", " + sample.state();
                AtomicInteger calls = new AtomicInteger();
                PlaceCandidate result = resolve(text, original, original, calls);
                assertEquals("roof", result.pid(), text);
                assertEquals(sample.lat(), result.lat());
                assertEquals(sample.lon(), result.lon());
                assertEquals(text, result.text());
                assertEquals(unit == null ? LocationResolution.Status.VERIFIED_BUILDING
                        : LocationResolution.Status.VERIFIED_UNIT, result.resolution().status(), text);
                assertEquals("corroborated_postal_locality", result.resolution().strategy());
        assertEquals("street-address-v13", result.resolution().policyVersion());
                assertNotNull(result.resolution().matchedAddress());
                assertEquals(2, calls.get(), "Postal evidence must reuse the existing reverse request");
            }
        }
    }

    @Test
    void radiusIsAnAdditionalBoundWhileProviderProofEstablishesTheStreetZip() throws Exception {
        // About 8.9km from the catalog reference point: the ZIP association is
        // postal-name evidence, not a claim to a legal municipal polygon.
        Sample nearEdge = new Sample("GROSSE POINTE", "MI", "48230", 42.4647, -82.9244);
        String original = body(nearEdge, "Sample Road", "MAP MUNICIPALITY", null);
        PlaceCandidate result = resolve("12345 SAMPLE RD, GROSSE POINTE, MI", original, original, new AtomicInteger());
        assertEquals("roof", result.pid());
        assertEquals(nearEdge.lat(), result.lat(), "Never use the postal reference point as the target");
        Sample outside = new Sample("GROSSE POINTE", "MI", "48230", 42.4847, -82.9244);
        String far = body(outside, "Sample Road", "MAP MUNICIPALITY", null);
        assertNull(resolve("12345 SAMPLE RD, GROSSE POINTE, MI", far, far, new AtomicInteger()).pid());
    }

    @Test
    void postalEvidenceNeverBridgesDifferentStreetIdentityOrLocalityConstraints() throws Exception {
        String valid = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        for (String invalid : List.of(valid.replace("12345", "12346"),
                valid.replace("Sample Road", "Other Road"), valid.replace("Sample Road", "North Sample Road"),
                valid.replace("Sample Road", "Sample Drive"), valid.replace("\"MI\"", "\"OH\""),
                valid.replace("\"US\"", "\"CA\""), valid.replace("\"48230\"", "\"48231\""),
                valid.replace("ROOFTOP", "RANGE_INTERPOLATED"), valid.replace("ROOFTOP", "GEOMETRIC_CENTER"),
                valid.replace("\"partial_match\":false", "\"partial_match\":true"),
                valid.replace("\"street_address\"", "\"locality\""),
                valid.replace("," + component("48230", "postal_code"), ""),
                valid.replace("42.3742093", "43.3742093"))) {
            PlaceCandidate result = resolve("12345 SAMPLE RD, GROSSE POINTE, MI", invalid, invalid, new AtomicInteger());
            assertNull(result.pid(), invalid);
        }
        for (String text : List.of("12345 SAMPLE RD, GROSSE POINTE PARK, MI",
                "12345 SAMPLE RD, GROSSE, MI", "12345 SAMPLE RD, GROSSE POINTE, OH",
                "12345 SAMPLE RD, GROSSE POINTE", "12345 SAMPLE RD, GROSSE POINTE, MI 48231",
                "12345 SAMPLE RD, GROSSE POINTE, MI 48231, USA")) {
            assertNull(resolve(text, valid, valid, new AtomicInteger()).pid(), text);
        }
    }

    @Test
    void reverseMustConfirmOriginalRooftopPlaceZipAndCanonicalLocality() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        for (String invalid : List.of("{\"status\":\"ZERO_RESULTS\",\"results\":[]}",
                original.replace("\"roof\"", "\"another-place\""),
                original.replace("42.3742093", "42.3743093"), original.replace("12345", "12346"),
                original.replace("Sample Road", "Sample Drive"), original.replace("MAP MUNICIPALITY", "OTHER CITY"),
                original.replace("\"48230\"", "\"48231\""), original.replace("ROOFTOP", "RANGE_INTERPOLATED"),
                original.replace("\"partial_match\":false", "\"partial_match\":true"),
                original.replace("\"street_address\"", "\"locality\""),
                original.replace("," + component("48230", "postal_code"), ""))) {
            assertNull(resolve("12345 SAMPLE RD, GROSSE POINTE, MI", original, invalid, new AtomicInteger()).pid(), invalid);
        }
    }

    @Test
    void duplicateOrInconsistentPostalComponentsCannotProveAnAlias() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        for (String bad : List.of(original.replace("],\"geometry\"", "," + component("48230", "postal_code") + "],\"geometry\""),
                original.replace("\"short_name\":\"48230\"", "\"short_name\":\"48231\""),
                original.replace("\"short_name\":\"MAP MUNICIPALITY\"", "\"short_name\":\"OTHER CITY\""))) {
            assertNull(resolve("12345 SAMPLE RD, GROSSE POINTE, MI", bad, bad, new AtomicInteger()).pid());
        }
    }

    @Test
    void postalEvidenceDoesNotMakeAnExistingInterpolatedLocalityProofAmbiguous() throws Exception {
        for (String unit : new String[]{null, "330"}) {
            String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", unit);
            String interpolation = body(LOCAL, "Sample Road", "GROSSE POINTE", unit)
                    .replace("\"roof\"", "\"interpolated\"").replace("ROOFTOP", "RANGE_INTERPOLATED")
                    .replace("42.3742093", "42.3742193");
            String text = "12345 SAMPLE RD" + (unit == null ? "" : " APT " + unit) + ", GROSSE POINTE, MI";
            for (String reverse : List.of(combine(original, interpolation), combine(interpolation, original))) {
                PlaceCandidate result = resolve(text, original, reverse, new AtomicInteger());
                assertEquals("roof", result.pid());
                assertEquals(LOCAL.lat(), result.lat());
                assertEquals("corroborated_locality", result.resolution().strategy());
            }
            // Two distinct old-style interpolated proofs must still stay ambiguous.
            String second = interpolation.replace("interpolated", "other").replace("42.3742193", "42.3742293");
            assertEquals(LocationResolution.Status.AMBIGUOUS,
                    resolve(text, original, combine(interpolation, second), new AtomicInteger()).resolution().status());
            if (unit != null) {
                String conflict = original.replace("\"330\"", "\"331\"");
                assertEquals("unit_conflict", resolve(text, original, combine(conflict, interpolation),
                        new AtomicInteger()).resolution().reasonCode());
            }
        }
    }

    @Test
    void inconsistentReverseStreetComponentsCannotProveAnAlias() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        for (String reverse : List.of(
                original.replace("\"short_name\":\"Sample Road\"", "\"short_name\":\"Other Road\""),
                original.replace("],\"geometry\"", "," + component("Other Road", "route") + "],\"geometry\""))) {
            assertNull(resolve("12345 SAMPLE RD, GROSSE POINTE, MI", original, reverse, new AtomicInteger()).pid());
        }
    }

    @Test
    void suppliedZipPlusFourMustBeConfirmedByBothAddressResults() throws Exception {
        String bare = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        String withSuffix = bare.replace(component("48230", "postal_code"),
                component("48230", "postal_code") + "," + component("0123", "postal_code_suffix"));
        String text = "12345 SAMPLE RD, GROSSE POINTE, MI 48230-0123";
        assertEquals("roof", resolve(text, withSuffix, withSuffix, new AtomicInteger()).pid());
        assertNull(resolve(text, bare, withSuffix, new AtomicInteger()).pid());
        assertNull(resolve(text, withSuffix, bare, new AtomicInteger()).pid());
        assertNull(resolve(text, withSuffix, withSuffix.replace("0123", "0124"), new AtomicInteger()).pid());
        assertNull(resolve(text.replace("0123", "0124"), withSuffix, withSuffix, new AtomicInteger()).pid());
    }

    @Test
    void exactUnitCannotHideAnotherRooftopBuildingAtTheRequestedCity() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", "330");
        String otherBuilding = body(LOCAL, "Sample Road", "GROSSE POINTE", null).replace("\"roof\"", "\"other\"");
        for (String reverse : List.of(combine(original, otherBuilding), combine(otherBuilding, original))) {
            PlaceCandidate result = resolve("12345 SAMPLE RD APT 330, GROSSE POINTE, MI", original, reverse, new AtomicInteger());
            assertNull(result.pid());
            assertEquals(LocationResolution.Status.AMBIGUOUS, result.resolution().status());
        }
    }

    @Test
    void competingReverseRooftopsAreAmbiguousRegardlessOfOrder() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null);
        String other = original.replace("\"roof\"", "\"other\"").replace("42.3742093", "42.3743093");
        for (String reverse : List.of(combine(original, other), combine(other, original))) {
            PlaceCandidate result = resolve("12345 SAMPLE RD, GROSSE POINTE, MI", original, reverse, new AtomicInteger());
            assertNull(result.pid());
            assertEquals(LocationResolution.Status.AMBIGUOUS, result.resolution().status());
        }
    }

    @Test
    void apartmentConflictsAreTerminalAndMissingApartmentRemainsBuildingOnly() throws Exception {
        String original = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", "330");
        String conflict = body(LOCAL, "Sample Road", "MAP MUNICIPALITY", "331");
        for (String reverse : List.of(conflict, combine(original, conflict), combine(conflict, original))) {
            AtomicInteger calls = new AtomicInteger();
            PlaceCandidate result = resolve("12345 SAMPLE RD APT 330, GROSSE POINTE, MI", original, reverse, calls);
            assertNull(result.pid());
            assertEquals("unit_conflict", result.resolution().reasonCode());
            assertEquals(2, calls.get());
        }
        PlaceCandidate building = resolve("12345 SAMPLE RD APT 330, GROSSE POINTE, MI", original,
                body(LOCAL, "Sample Road", "MAP MUNICIPALITY", null), new AtomicInteger());
        assertEquals(LocationResolution.Status.VERIFIED_BUILDING, building.resolution().status());
        assertEquals("unit_not_confirmed", building.resolution().reasonCode());
        assertTrue(building.text().contains("APT 330"));
    }

    private static PlaceCandidate resolve(String text, String forward, String reverse, AtomicInteger calls) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            calls.incrementAndGet();
            byte[] bytes = (exchange.getRequestURI().getRawQuery().contains("latlng=") ? reverse : forward)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {output.write(bytes);}
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("fixture-key", "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/geocode", "us", 1000, 2000, 2, 4, 0);
            return client.batchGeocode("owner", List.of(new PlaceCandidate(text, text, "fixture.png", 0,
                    0, 0, null, 3, false, List.of()))).getFirst();
        } finally {server.stop(0);}
    }

    private static String body(Sample sample, String route, String city, String unit) {
        return "{\"status\":\"OK\",\"results\":[{\"place_id\":\"roof\",\"partial_match\":false,"
                + "\"formatted_address\":\"12345 " + route + ", " + city + ", " + sample.state() + " " + sample.zip() + "\","
                + "\"types\":[\"street_address\"],\"address_components\":["
                + component("12345", "street_number") + "," + component(route, "route") + ","
                + component(city, "locality") + "," + component(sample.state(), "administrative_area_level_1") + ","
                + component("US", "country") + "," + component(sample.zip(), "postal_code")
                + (unit == null ? "" : "," + component(unit, "subpremise"))
                + "],\"geometry\":{\"location_type\":\"ROOFTOP\",\"location\":{\"lat\":" + sample.lat()
                + ",\"lng\":" + sample.lon() + "}}}]}";
    }

    private static String component(String value, String type) {
        return "{\"long_name\":\"" + value + "\",\"short_name\":\"" + value + "\",\"types\":[\"" + type + "\"]}";
    }

    private static String combine(String first, String second) {
        return first.substring(0, first.length() - 2) + "," + second.substring(second.indexOf("[{" ) + 1);
    }
}
