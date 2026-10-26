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

class UnitParentRecoveryTest {
    private static final Fixture CHERRYWOOD = new Fixture(
            "5845 CHERRYWOOD APT2601, WEST BLOOMFIELD MI", "5845", "CHERRYWOOD", "2601",
            "WEST BLOOMFIELD", "MI", null, "West Bloomfield Township", "48322", 42.50, -83.36);
    private static final Fixture SYNTHETIC = new Fixture(
            "100 MAIN APT4, TESTVILLE, MI", "100", "MAIN", "4", "TESTVILLE", "MI",
            "Testville", null, "48000", 42.30, -83.20);
    private static final String ZERO_RESULTS = "{\"status\":\"ZERO_RESULTS\",\"results\":[]}";

    @Test
    void compactCherrywoodUnitAndSyntheticUnitNeedTwoUnitAndTwoParentProofs() throws Exception {
        for (Fixture fixture : List.of(CHERRYWOOD, SYNTHETIC)) {
            Result parent = fixture.parent();
            Result neighboringHouse = parent.withPlaceId("neighboring-house")
                    .withHouse(fixture.house.equals("5845") ? "5821" : "102")
                    .withPoint(fixture.latitude + 0.0003, fixture.longitude);

            for (List<Result> reverseResults : List.of(
                    List.of(parent, neighboringHouse), List.of(neighboringHouse, parent))) {
                Probe probe = resolve(fixture, Responses.success(fixture).with(Phase.REVERSE, reverseResults));

                assertEquals(fixture.unitPlaceId(), probe.result().pid(), fixture.input);
                assertEquals(LocationResolution.Status.VERIFIED_UNIT,
                        probe.result().resolution().status(), fixture.input);
                assertEquals(fixture.input, probe.result().text(), "Keep the supplied OCR text unchanged");
                assertEquals(4, probe.requests().size(), "Two unit and two parent attestations are required");

                Map<String, String> original = probe.requests().get(0);
                assertEquals(fixture.input, original.get("address"));
                assertFalse(original.containsKey("components"));
                assertFalse(original.containsKey("latlng"));

                Map<String, String> unitProof = probe.requests().get(1);
                assertEquals(fixture.house + " " + fixture.route + " UNIT " + fixture.unit,
                        unitProof.get("address"));
                assertTrue(unitProof.containsKey("components"));
                assertEquals("locality:" + fixture.city + "|administrative_area:" + fixture.state + "|country:US",
                        unitProof.get("components"));
                assertFalse(unitProof.containsKey("latlng"));

                assertTrue(probe.requests().get(2).containsKey("latlng"));

                Map<String, String> parentProof = probe.requests().get(3);
                assertEquals(fixture.house + " " + fixture.route, parentProof.get("address"));
                assertTrue(parentProof.containsKey("components"));
                assertFalse(parentProof.containsKey("latlng"));
                assertFalse(hasUnitMarker(parentProof.get("address"), fixture.unit));
            }
        }
    }

    @Test
    void alreadyConfirmedUnitAndItsParentAreNotCompetingDestinations() throws Exception {
        for (List<Result> order : List.of(List.of(CHERRYWOOD.unitResult(), CHERRYWOOD.parent()),
                List.of(CHERRYWOOD.parent(), CHERRYWOOD.unitResult()))) {
            Probe probe = resolve(CHERRYWOOD, Responses.success(CHERRYWOOD).with(Phase.REVERSE, order));
            assertEquals(CHERRYWOOD.unitPlaceId(), probe.result().pid());
            assertEquals(LocationResolution.Status.VERIFIED_UNIT, probe.result().resolution().status());
            assertEquals(3, probe.requests().size(), "Three matching unit proofs need no parent-only fourth query");
        }
    }

    @Test
    void reverseParentMustBeAnExactUnitlessRooftopForTheSameTypedAddressWithinTwoMetres() throws Exception {
        Fixture fixture = CHERRYWOOD;
        Result parent = fixture.parent();
        List<Result> invalidReverseResults = List.of(
                parent.withUnit(fixture.unit),
                parent.withUnit("2602"),
                parent.withTypes("street_address", "subpremise"),
                parent.withFormattedAddress("5845 Cherrywood #2602, West Bloomfield Township, MI 48322, USA"),
                parent.withHouse("5846"),
                parent.withRoute("Other Road"),
                parent.withMunicipality("Another Township"),
                parent.withExtra(new Component("Other City", "Other City", "locality")),
                parent.withState("Ohio", "OH"),
                parent.withZip("48323"),
                parent.withExtra(new Component("Canada", "CA", "country")),
                parent.withExtra(new Component("48322", "48322", "postal_code")),
                parent.withExtra(new Component("Other Road", "Other Road", "route")),
                parent.withLocationType("RANGE_INTERPOLATED"),
                parent.withPartial(true),
                parent.withPointOffsetMetres(2.1));

        for (Result invalid : invalidReverseResults) {
            Probe probe = resolve(fixture, Responses.success(fixture).with(Phase.REVERSE, List.of(invalid)));
            assertNull(probe.result().pid(), invalid.describe());
            assertNotEquals(LocationResolution.Status.VERIFIED_UNIT,
                    probe.result().resolution().status(), invalid.describe());
        }
    }

    @Test
    void fourthParentProofMustAttestTheReverseParentIdentityAndRemainUnitless() throws Exception {
        Fixture fixture = CHERRYWOOD;
        Result parent = fixture.parent();
        List<Result> invalidParentProofs = List.of(
                parent.withPlaceId("different-parent"),
                parent.withPointOffsetMetres(2.1),
                parent.withUnit(fixture.unit),
                parent.withUnit("2602"),
                parent.withTypes("street_address", "subpremise"),
                parent.withFormattedAddress("5845 Cherrywood Apt 2602, West Bloomfield Township, MI 48322, USA"),
                parent.withHouse("5846"),
                parent.withRoute("Other Road"),
                parent.withMunicipality("Another Township"),
                parent.withExtra(new Component("Other City", "Other City", "locality")),
                parent.withState("Ohio", "OH"),
                parent.withZip("48323"),
                parent.withExtra(new Component("Canada", "CA", "country")),
                parent.withExtra(new Component("48322", "48322", "postal_code")),
                parent.withExtra(new Component("Other Road", "Other Road", "route")));

        for (Result invalid : invalidParentProofs) {
            Probe probe = resolve(fixture, Responses.success(fixture).with(Phase.PARENT, List.of(invalid)));
            assertNull(probe.result().pid(), invalid.describe());
            assertNotEquals(LocationResolution.Status.VERIFIED_UNIT,
                    probe.result().resolution().status(), invalid.describe());
        }
    }

    @Test
    void fourthProofCannotDriftBeyondTheUnitRooftopByFollowingAnOffsetParent() throws Exception {
        Probe probe = resolve(CHERRYWOOD, Responses.success(CHERRYWOOD)
                .with(Phase.REVERSE, List.of(CHERRYWOOD.parent().withPointOffsetMetres(1.9)))
                .with(Phase.PARENT, List.of(CHERRYWOOD.parent().withPointOffsetMetres(3.8))));
        assertNull(probe.result().pid());
    }

    @Test
    void coLocatedCompetingPidsOrUnitsRemainAmbiguousRegardlessOfResultOrder() throws Exception {
        Fixture fixture = CHERRYWOOD;
        Result parent = fixture.parent();
        List<List<Result>> reverseCompetitors = List.of(
                List.of(parent, parent.withPlaceId("second-parent")),
                List.of(parent, fixture.unitResult().withPlaceId("second-unit")),
                List.of(parent, fixture.unitResult().withPlaceId("wrong-unit").withUnit("2602")));

        for (List<Result> pair : reverseCompetitors) {
            for (List<Result> order : List.of(pair, List.of(pair.get(1), pair.get(0)))) {
                Probe probe = resolve(fixture, Responses.success(fixture).with(Phase.REVERSE, order));
                assertNull(probe.result().pid(), order.toString());
                assertEquals(LocationResolution.Status.AMBIGUOUS,
                        probe.result().resolution().status(), order.toString());
            }
        }
    }

    @Test
    void forwardUnitProofsMustKeepTheSamePlaceIdentityEvenAtTheSameCoordinates() throws Exception {
        Fixture fixture = CHERRYWOOD;
        Result unit = fixture.unitResult();
        for (List<Result> results : List.of(
                List.of(unit, unit.withPlaceId("competing-unit")),
                List.of(unit.withPlaceId("competing-unit"), unit))) {
            Probe original = resolve(fixture, Responses.success(fixture).with(Phase.ORIGINAL, results));
            assertNull(original.result().pid(), results.toString());

            Probe constrained = resolve(fixture, Responses.success(fixture).with(Phase.STRUCTURED, results));
            assertNull(constrained.result().pid(), results.toString());
        }
    }

    private static Probe resolve(Fixture fixture, Responses responses) throws Exception {
        List<Map<String, String>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/geocode", exchange -> {
            Map<String, String> query = parameters(exchange);
            requests.add(query);
            String body = responses.forQuery(fixture, query);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        server.start();
        try {
            GeocodingClient client = new GeocodingClient("fixture-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/geocode", "us",
                    100, 100, 1, 1, 0);
            PlaceCandidate candidate = new PlaceCandidate(fixture.input, fixture.input,
                    "unit-parent-fixture.png", 0, 0, 0, null);
            PlaceCandidate result = client.batchGeocode("owner", List.of(candidate)).getFirst();
            return new Probe(result, List.copyOf(requests));
        } finally {
            server.stop(0);
        }
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

    private static String body(List<Result> results) {
        if (results.isEmpty()) return ZERO_RESULTS;
        return "{\"status\":\"OK\",\"results\":["
                + String.join(",", results.stream().map(Result::json).toList()) + "]}";
    }

    private static boolean hasUnitMarker(String address, String unit) {
        String normalized = address.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        return normalized.contains("APT " + unit) || normalized.contains("APT" + unit)
                || normalized.contains("UNIT " + unit);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private enum Phase { ORIGINAL, STRUCTURED, REVERSE, PARENT }

    private record Probe(PlaceCandidate result, List<Map<String, String>> requests) { }

    private record Fixture(String input, String house, String route, String unit, String city, String state,
                           String locality, String municipality, String zip, double latitude, double longitude) {
        String unitPlaceId() { return "unit-" + route.toLowerCase(Locale.ROOT); }
        String parentPlaceId() { return "parent-" + route.toLowerCase(Locale.ROOT); }
        Result unitResult() { return new Result(this, unitPlaceId(), unit); }
        Result parent() { return new Result(this, parentPlaceId(), null); }
    }

    private record Responses(List<Result> original, List<Result> structured, List<Result> reverse,
                             List<Result> parent) {
        private Responses {
            original = List.copyOf(original);
            structured = List.copyOf(structured);
            reverse = List.copyOf(reverse);
            parent = List.copyOf(parent);
        }

        static Responses success(Fixture fixture) {
            return new Responses(List.of(fixture.unitResult()), List.of(fixture.unitResult()),
                    List.of(fixture.parent()), List.of(fixture.parent()));
        }

        Responses with(Phase phase, List<Result> replacement) {
            return switch (phase) {
                case ORIGINAL -> new Responses(replacement, structured, reverse, parent);
                case STRUCTURED -> new Responses(original, replacement, reverse, parent);
                case REVERSE -> new Responses(original, structured, replacement, parent);
                case PARENT -> new Responses(original, structured, reverse, replacement);
            };
        }

        String forQuery(Fixture fixture, Map<String, String> query) {
            if (query.containsKey("latlng")) return body(reverse);
            String address = query.getOrDefault("address", "");
            if (query.containsKey("components")) {
                return body(hasUnitMarker(address, fixture.unit) ? structured : parent);
            }
            return address.equals(fixture.input) ? body(original) : ZERO_RESULTS;
        }
    }

    private record Component(String longName, String shortName, String type) {
        String json() {
            return "{\"long_name\":" + quote(longName) + ",\"short_name\":" + quote(shortName)
                    + ",\"types\":[" + quote(type) + "]}";
        }
    }

    private static final class Result {
        private final Fixture fixture;
        private String placeId;
        private String house;
        private String route;
        private String locality;
        private String municipality;
        private String stateLong = "Michigan";
        private String stateShort;
        private String zip;
        private String unit;
        private double latitude;
        private double longitude;
        private String locationType = "ROOFTOP";
        private boolean partial;
        private String formattedOverride;
        private List<String> types;
        private final List<Component> extraComponents = new ArrayList<>();

        private Result(Fixture fixture, String placeId, String unit) {
            this.fixture = fixture;
            this.placeId = placeId;
            this.house = fixture.house;
            this.route = titleCase(fixture.route);
            this.locality = fixture.locality;
            this.municipality = fixture.municipality;
            this.stateShort = fixture.state;
            this.zip = fixture.zip;
            this.unit = unit;
            this.latitude = fixture.latitude;
            this.longitude = fixture.longitude;
            this.types = unit == null ? new ArrayList<>(List.of("premise", "street_address"))
                    : new ArrayList<>(List.of("street_address", "subpremise"));
        }

        private Result(Result source) {
            fixture = source.fixture;
            placeId = source.placeId;
            house = source.house;
            route = source.route;
            locality = source.locality;
            municipality = source.municipality;
            stateLong = source.stateLong;
            stateShort = source.stateShort;
            zip = source.zip;
            unit = source.unit;
            latitude = source.latitude;
            longitude = source.longitude;
            locationType = source.locationType;
            partial = source.partial;
            formattedOverride = source.formattedOverride;
            types = new ArrayList<>(source.types);
            extraComponents.addAll(source.extraComponents);
        }

        Result withPlaceId(String value) { Result copy = new Result(this); copy.placeId = value; return copy; }
        Result withTypes(String... values) { Result copy = new Result(this); copy.types = new ArrayList<>(List.of(values)); return copy; }
        Result withFormattedAddress(String value) { Result copy = new Result(this); copy.formattedOverride = value; return copy; }
        Result withHouse(String value) { Result copy = new Result(this); copy.house = value; return copy; }
        Result withRoute(String value) { Result copy = new Result(this); copy.route = value; return copy; }
        Result withMunicipality(String value) { Result copy = new Result(this); copy.municipality = value; return copy; }
        Result withState(String longName, String shortName) {
            Result copy = new Result(this); copy.stateLong = longName; copy.stateShort = shortName; return copy;
        }
        Result withZip(String value) { Result copy = new Result(this); copy.zip = value; return copy; }
        Result withUnit(String value) {
            Result copy = new Result(this);
            copy.unit = value;
            copy.types = value == null ? new ArrayList<>(List.of("premise", "street_address"))
                    : new ArrayList<>(List.of("street_address", "subpremise"));
            return copy;
        }
        Result withLocationType(String value) { Result copy = new Result(this); copy.locationType = value; return copy; }
        Result withPartial(boolean value) { Result copy = new Result(this); copy.partial = value; return copy; }
        Result withPoint(double lat, double lon) {
            Result copy = new Result(this); copy.latitude = lat; copy.longitude = lon; return copy;
        }
        Result withPointOffsetMetres(double metres) {
            return withPoint(latitude + metres / 111_195d, longitude);
        }
        Result withExtra(Component component) {
            Result copy = new Result(this); copy.extraComponents.add(component); return copy;
        }

        String describe() {
            return "pid=" + placeId + ", unit=" + unit + ", house=" + house + ", route=" + route
                    + ", municipality=" + municipality + ", zip=" + zip + ", point=" + latitude + "," + longitude
                    + ", locationType=" + locationType;
        }

        String json() {
            List<Component> components = new ArrayList<>();
            if (unit != null) components.add(new Component(unit, unit, "subpremise"));
            components.add(new Component(house, house, "street_number"));
            components.add(new Component(route, route, "route"));
            if (locality != null) components.add(new Component(locality, locality, "locality"));
            if (municipality != null) {
                components.add(new Component(municipality, municipality, "administrative_area_level_3"));
            }
            components.add(new Component("Sample County", "Sample County", "administrative_area_level_2"));
            components.add(new Component(stateLong, stateShort, "administrative_area_level_1"));
            components.add(new Component("United States", "US", "country"));
            components.add(new Component(zip, zip, "postal_code"));
            components.addAll(extraComponents);
            String formattedLocality = locality == null ? municipality : locality;
            String formatted = formattedOverride != null ? formattedOverride : house + " " + route + (unit == null ? "" : " " + unit)
                    + (formattedLocality == null ? "" : ", " + formattedLocality)
                    + ", " + stateShort + " " + zip;
            return "{\"place_id\":" + quote(placeId) + ",\"partial_match\":" + partial
                    + ",\"types\":[" + String.join(",", types.stream().map(UnitParentRecoveryTest::quote).toList())
                    + "],\"formatted_address\":" + quote(formatted) + ",\"address_components\":["
                    + String.join(",", components.stream().map(Component::json).toList())
                    + "],\"geometry\":{\"location_type\":" + quote(locationType)
                    + ",\"location\":{\"lat\":" + latitude + ",\"lng\":" + longitude + "}}}";
        }

        private static String titleCase(String value) {
            String lower = value.toLowerCase(Locale.ROOT);
            return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
        }
    }
}
