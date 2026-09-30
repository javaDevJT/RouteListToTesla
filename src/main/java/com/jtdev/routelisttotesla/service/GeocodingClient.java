package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeocodingClient {
    public static final String GEOCODING_VERSION = "street-address-v2";

    private static final int DEFAULT_OWNER_CALLS_PER_HOUR = 1_000;
    private static final int DEFAULT_GLOBAL_CALLS_PER_HOUR = 2_000;
    private static final int DEFAULT_OWNER_CONCURRENT_BATCHES = 2;
    private static final int DEFAULT_GLOBAL_CONCURRENT_BATCHES = 4;
    private static final long DEFAULT_MINIMUM_DELAY_MILLIS = 60;
    private static final long HOUR_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final Pattern SECONDARY_UNIT = Pattern.compile(
            "(?:(?:,\\s*|\\s+)" + AddressExtractor.UNIT_LABELS + "\\.?\\s*#?\\s*"
                    + "(?:[A-Z]?\\d+[A-Z0-9-]*|[A-Z](?:-\\d+[A-Z]?)?)\\s*)+(?=,|$)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HOUSE_NUMBER = Pattern.compile("^(\\d+(?:\\s+\\d+/\\d+|[./-]\\d+)?[A-Z]?)\\b");

    private final String apiKey;
    private final String geocodeUrl;
    private final String regionBias;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final int ownerCallsPerHour;
    private final int globalCallsPerHour;
    private final int ownerConcurrentBatches;
    private final int globalConcurrentBatches;
    private final long minimumDelayNanos;

    // ponytail: limits are process-local and reset on restart; use shared storage only if the app scales to replicas.
    private final Object budgetLock = new Object();
    private final Map<String, Usage> ownerUsage = new HashMap<>();
    private final Map<String, Integer> activeOwnerBatches = new HashMap<>();
    private final Object pacingLock = new Object();
    private Usage globalUsage = new Usage();
    private int activeGlobalBatches;
    private long nextRequestAtNanos;
    private boolean pacingStarted;

    @Autowired
    public GeocodingClient(@Value("${app.google.apiKey}") String apiKey,
                           @Value("${app.google.geocodeUrl}") String geocodeUrl,
                           @Value("${app.google.regionBias:us}") String regionBias) {
        this(apiKey, geocodeUrl, regionBias, DEFAULT_OWNER_CALLS_PER_HOUR, DEFAULT_GLOBAL_CALLS_PER_HOUR,
                DEFAULT_OWNER_CONCURRENT_BATCHES, DEFAULT_GLOBAL_CONCURRENT_BATCHES, DEFAULT_MINIMUM_DELAY_MILLIS);
    }

    GeocodingClient(String apiKey, String geocodeUrl, String regionBias, int ownerCallsPerHour,
                    int globalCallsPerHour, int ownerConcurrentBatches, int globalConcurrentBatches,
                    long minimumDelayMillis) {
        if (ownerCallsPerHour < 1 || globalCallsPerHour < 1 || ownerConcurrentBatches < 1
                || globalConcurrentBatches < 1 || minimumDelayMillis < 0) {
            throw new IllegalArgumentException("Geocoding limits must be positive");
        }
        this.apiKey = apiKey;
        this.geocodeUrl = geocodeUrl;
        this.regionBias = regionBias;
        this.ownerCallsPerHour = ownerCallsPerHour;
        this.globalCallsPerHour = globalCallsPerHour;
        this.ownerConcurrentBatches = ownerConcurrentBatches;
        this.globalConcurrentBatches = globalConcurrentBatches;
        this.minimumDelayNanos = TimeUnit.MILLISECONDS.toNanos(minimumDelayMillis);
    }

    public List<PlaceCandidate> batchGeocode(String owner, List<PlaceCandidate> inputs) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("An account owner is required for geocoding");
        if (inputs == null) throw new IllegalArgumentException("Geocoding inputs are required");
        if (inputs.isEmpty()) return List.of();
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("Configure the Google geocoding API key");

        Map<String, PlaceCandidate> uniqueQueries = new LinkedHashMap<>();
        for (PlaceCandidate candidate : inputs) {
            if (candidate == null || candidate.text() == null || candidate.text().isBlank()) {
                throw new IllegalArgumentException("Every geocoding query must contain address text");
            }
            uniqueQueries.putIfAbsent(candidate.text(), candidate);
        }

        reserve(owner, uniqueQueries.size(), true);
        try {
            Map<String, PlaceCandidate> resultsByQuery = new HashMap<>();
            for (Map.Entry<String, PlaceCandidate> query : uniqueQueries.entrySet()) {
                resultsByQuery.put(query.getKey(), geocodeOne(owner, query.getValue()));
            }
            return inputs.stream().map(candidate -> {
                PlaceCandidate result = resultsByQuery.get(candidate.text());
                return candidate.withLatLonPid(result.lat(), result.lon(), result.pid());
            }).toList();
        } finally {
            release(owner);
        }
    }

    private void reserve(String owner, int calls, boolean newBatch) {
        long now = System.nanoTime();
        synchronized (budgetLock) {
            globalUsage.prune(now);
            ownerUsage.entrySet().removeIf(entry -> {
                entry.getValue().prune(now);
                return entry.getValue().calls == 0 && !activeOwnerBatches.containsKey(entry.getKey());
            });
            Usage account = ownerUsage.computeIfAbsent(owner, ignored -> new Usage());
            account.prune(now);
            int activeForOwner = activeOwnerBatches.getOrDefault(owner, 0);
            if (calls > ownerCallsPerHour || calls > globalCallsPerHour
                    || account.calls + calls > ownerCallsPerHour || globalUsage.calls + calls > globalCallsPerHour
                    || (newBatch && (activeForOwner >= ownerConcurrentBatches || activeGlobalBatches >= globalConcurrentBatches))) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Geocoding rate or concurrency limit exceeded; retry later");
            }
            account.reserve(now, calls);
            globalUsage.reserve(now, calls);
            if (newBatch) {
                activeOwnerBatches.put(owner, activeForOwner + 1);
                activeGlobalBatches++;
            }
        }
    }

    private void release(String owner) {
        synchronized (budgetLock) {
            int active = activeOwnerBatches.getOrDefault(owner, 0);
            if (active <= 1) activeOwnerBatches.remove(owner);
            else activeOwnerBatches.put(owner, active - 1);
            activeGlobalBatches--;
        }
    }

    private HttpResponse<String> sendWithGlobalPacing(HttpRequest request) {
        CompletableFuture<HttpResponse<String>> call;
        synchronized (pacingLock) {
            long now = System.nanoTime();
            long waitNanos = pacingStarted ? nextRequestAtNanos - now : 0;
            if (pacingStarted && waitNanos > 0) {
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Geocoding interrupted");
                }
            }
            call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            nextRequestAtNanos = System.nanoTime() + minimumDelayNanos;
            pacingStarted = true;
        }
        try {
            return call.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Geocoding interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException("Geocoding service is unavailable");
        }
    }

    private PlaceCandidate geocodeOne(String owner, PlaceCandidate candidate) {
        PlaceCandidate resolved = lookup(candidate, candidate.text());
        String primaryAddress = withoutSecondaryUnit(candidate.text());
        if (resolved.pid() != null || primaryAddress.equals(candidate.text())) return resolved;
        reserve(owner, 1, false);
        return lookup(candidate, primaryAddress);
    }

    private PlaceCandidate lookup(PlaceCandidate candidate, String address) {
        URI uri = URI.create(geocodeUrl + "?address=" + encode(address)
                + "&region=" + encode(regionBias) + "&key=" + encode(apiKey));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> response = sendWithGlobalPacing(request);
        if (response.statusCode() != 200) throw new IllegalStateException("Geocoding returned HTTP " + response.statusCode());
        JsonNode body;
        try {
            body = mapper.readTree(response.body());
        } catch (JacksonException e) {
            throw new IllegalStateException("Geocoding returned an invalid response");
        }
        if (body == null) throw new IllegalStateException("Geocoding returned an empty response");
        String status = body.path("status").asString();
        if ("OK".equals(status) && body.path("results").isArray()) {
            String requestedHouseNumber = houseNumber(candidate.text());
            for (JsonNode match : body.path("results")) {
                boolean addressType = hasType(match, "street_address") || hasType(match, "premise")
                        || hasType(match, "subpremise");
                if (match.path("partial_match").asBoolean(false) || !addressType) continue;

                String streetNumber = addressComponent(match, "street_number");
                if (streetNumber == null || addressComponent(match, "route") == null
                        || !houseNumbersMatch(requestedHouseNumber, streetNumber)) continue;

                JsonNode location = match.path("geometry").path("location");
                if (location.path("lat").isNumber() && location.path("lng").isNumber()) {
                    double latitude = location.path("lat").asDouble();
                    double longitude = location.path("lng").asDouble();
                    String placeId = match.path("place_id").asString(null);
                    if (Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                            && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                            && (latitude != 0 || longitude != 0) && placeId != null
                            && placeId.matches("[A-Za-z0-9_-]{1,512}")) {
                        return candidate.withLatLonPid(latitude, longitude, placeId);
                    }
                }
            }
        } else if (!"ZERO_RESULTS".equals(status)) {
            throw new IllegalStateException("Geocoding failed; check API access and quota");
        }
        // Keep unresolved stops visible for review; never silently remove them.
        return candidate.withLatLonPid(0, 0, null);
    }

    private static String withoutSecondaryUnit(String address) {
        Matcher unit = SECONDARY_UNIT.matcher(address);
        if (!unit.find()) return address;
        return unit.replaceFirst("")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*,\\s*", ", ")
                .replaceAll("(?:,\\s*){2,}", ", ")
                .replaceAll(",\\s*$", "")
                .trim();
    }

    private static String houseNumber(String address) {
        Matcher matcher = HOUSE_NUMBER.matcher(AddressExtractor.normalize(address));
        return matcher.find() ? canonicalHouseNumber(matcher.group(1)) : null;
    }

    private static boolean houseNumbersMatch(String requested, String returned) {
        return requested == null || requested.equals(canonicalHouseNumber(returned));
    }

    private static String canonicalHouseNumber(String value) {
        return value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String addressComponent(JsonNode result, String type) {
        for (JsonNode component : result.path("address_components")) {
            if (hasType(component, type)) {
                String name = component.path("long_name").asString(null);
                if (name != null && !name.isBlank()) return name;
            }
        }
        return null;
    }

    private static boolean hasType(JsonNode result, String type) {
        for (JsonNode resultType : result.path("types")) {
            if (type.equals(resultType.asString())) return true;
        }
        return false;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Usage {
        private final ArrayDeque<Reservation> reservations = new ArrayDeque<>();
        private int calls;

        private void prune(long now) {
            while (!reservations.isEmpty() && now - reservations.peekFirst().atNanos >= HOUR_NANOS) {
                calls -= reservations.removeFirst().calls;
            }
        }

        private void reserve(long now, int count) {
            reservations.addLast(new Reservation(now, count));
            calls += count;
        }
    }

    private record Reservation(long atNanos, int calls) { }
}
