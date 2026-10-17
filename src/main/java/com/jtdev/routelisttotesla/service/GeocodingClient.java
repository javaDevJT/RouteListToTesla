package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeocodingClient {
    public static final String GEOCODING_VERSION = "street-address-v3";

    private static final Logger LOGGER = LoggerFactory.getLogger(GeocodingClient.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long FIRST_RETRY_DELAY_MILLIS = 100;
    private static final long SECOND_RETRY_DELAY_MILLIS = 200;
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
    private static final Pattern UNIT_MARKER_WITH_VALUE = Pattern.compile(
            "(?i)(?:^|\\s)" + AddressExtractor.UNIT_LABELS + "\\.?\\s*#?\\s*"
                    + "(?:[A-Z]?\\d+[A-Z0-9-]*|[A-Z](?:-\\d+[A-Z]?)?)(?=\\s|$)");
    private static final Pattern TRAILING_POSTAL_CODE = Pattern.compile("(?i)^(.*?)(?:\\s+)?(\\d{5}(?:-\\d{4})?)$");
    private static final Set<String> CARDINAL_DIRECTIONS = Set.of("N", "S", "E", "W", "NORTH", "SOUTH", "EAST", "WEST");
    private static final Pattern EXPLICIT_COMPLEX_MARKER = Pattern.compile(
            "(?i)(?:^|\\s)(?:APARTMENTS?|RESIDENCES?|TOWNHOMES?|CONDOMINIUMS?|CONDOS?|"
                    + "VILLAGES?|VILLAS|GARDENS|MANOR|ESTATES|TOWERS|CROSSING|LANDING|COMPLEX)\\.?$");
    private static final Pattern ROUTE_TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)?");
    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "DC", "FL", "GA", "HI", "ID", "IL",
            "IN", "IA", "KS", "KY", "LA", "ME", "MD", "MA", "MI", "MN", "MS", "MO", "MT", "NE",
            "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC", "SD",
            "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY", "PR", "GU", "VI", "AS", "MP");
    private static final Map<String, String> US_STATE_CODES_BY_NAME = Map.ofEntries(
            Map.entry("ALABAMA", "AL"), Map.entry("ALASKA", "AK"), Map.entry("ARIZONA", "AZ"),
            Map.entry("ARKANSAS", "AR"), Map.entry("CALIFORNIA", "CA"), Map.entry("COLORADO", "CO"),
            Map.entry("CONNECTICUT", "CT"), Map.entry("DELAWARE", "DE"),
            Map.entry("DISTRICT OF COLUMBIA", "DC"), Map.entry("FLORIDA", "FL"),
            Map.entry("GEORGIA", "GA"), Map.entry("HAWAII", "HI"), Map.entry("IDAHO", "ID"),
            Map.entry("ILLINOIS", "IL"), Map.entry("INDIANA", "IN"), Map.entry("IOWA", "IA"),
            Map.entry("KANSAS", "KS"), Map.entry("KENTUCKY", "KY"), Map.entry("LOUISIANA", "LA"),
            Map.entry("MAINE", "ME"), Map.entry("MARYLAND", "MD"), Map.entry("MASSACHUSETTS", "MA"),
            Map.entry("MICHIGAN", "MI"), Map.entry("MINNESOTA", "MN"), Map.entry("MISSISSIPPI", "MS"),
            Map.entry("MISSOURI", "MO"), Map.entry("MONTANA", "MT"), Map.entry("NEBRASKA", "NE"),
            Map.entry("NEVADA", "NV"), Map.entry("NEW HAMPSHIRE", "NH"), Map.entry("NEW JERSEY", "NJ"),
            Map.entry("NEW MEXICO", "NM"), Map.entry("NEW YORK", "NY"), Map.entry("NORTH CAROLINA", "NC"),
            Map.entry("NORTH DAKOTA", "ND"), Map.entry("OHIO", "OH"), Map.entry("OKLAHOMA", "OK"),
            Map.entry("OREGON", "OR"), Map.entry("PENNSYLVANIA", "PA"), Map.entry("RHODE ISLAND", "RI"),
            Map.entry("SOUTH CAROLINA", "SC"), Map.entry("SOUTH DAKOTA", "SD"),
            Map.entry("TENNESSEE", "TN"), Map.entry("TEXAS", "TX"), Map.entry("UTAH", "UT"),
            Map.entry("VERMONT", "VT"), Map.entry("VIRGINIA", "VA"), Map.entry("WASHINGTON", "WA"),
            Map.entry("WEST VIRGINIA", "WV"), Map.entry("WISCONSIN", "WI"), Map.entry("WYOMING", "WY"));

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
            Throwable cause = unwrap(e);
            if (cause instanceof IOException) throw new TransientTransportFailure(cause);
            throw new IllegalStateException("Geocoding service is unavailable");
        }
    }

    private PlaceCandidate geocodeOne(String owner, PlaceCandidate candidate) {
        List<String> variants = addressVariants(candidate.text());
        for (int index = 0; index < variants.size(); index++) {
            if (index > 0) reserve(owner, 1, false);
            PlaceCandidate resolved = lookup(owner, candidate, variants.get(index));
            if (resolved != null) return resolved;
        }
        // Keep unresolved stops visible for review; never silently remove them.
        return candidate.withLatLonPid(0, 0, null);
    }

    private PlaceCandidate lookup(String owner, PlaceCandidate candidate, String address) {
        URI uri = URI.create(geocodeUrl + "?address=" + encode(address)
                + "&region=" + encode(regionBias) + "&key=" + encode(apiKey));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                waitBeforeRetry(attempt - 1);
                reserve(owner, 1, false);
            }

            HttpResponse<String> response;
            try {
                response = sendWithGlobalPacing(request);
            } catch (TransientTransportFailure e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logFailure(attempt, "transport", null, null, cause);
                if (attempt == MAX_ATTEMPTS) throw new IllegalStateException("Geocoding service is unavailable");
                continue;
            }

            int httpStatus = response.statusCode();
            if (httpStatus != 200) {
                if (isRetryableHttpStatus(httpStatus)) {
                    logFailure(attempt, "http_transient", httpStatus, null, null);
                    if (attempt == MAX_ATTEMPTS) throw new IllegalStateException("Geocoding service is unavailable");
                    continue;
                }
                logFailure(attempt, "http_not_retried", httpStatus, null, null);
                throw new IllegalStateException("Geocoding returned HTTP " + httpStatus);
            }

            JsonNode body;
            try {
                body = mapper.readTree(response.body());
            } catch (JacksonException e) {
                logFailure(attempt, "invalid_response", httpStatus, null, e);
                throw new IllegalStateException("Geocoding returned an invalid response");
            }
            if (body == null) {
                logFailure(attempt, "invalid_response", httpStatus, null, null);
                throw new IllegalStateException("Geocoding returned an empty response");
            }
            String status = body.path("status").asString();
            if ("UNKNOWN_ERROR".equals(status)) {
                logFailure(attempt, "provider_transient", httpStatus, status, null);
                if (attempt == MAX_ATTEMPTS) throw new IllegalStateException("Geocoding service is unavailable");
                continue;
            }
            if ("OK".equals(status) && body.path("results").isArray()) {
                String requestedHouseNumber = houseNumber(address);
                AddressParts requestedAddress = addressParts(address);
                String requestedRoute = requestedAddress.route();
                LocalityHint localityHint = requestedAddress.localityHint();
                for (JsonNode match : body.path("results")) {
                    boolean addressType = hasType(match, "street_address") || hasType(match, "premise")
                            || hasType(match, "subpremise");
                    if (match.path("partial_match").asBoolean(false) || !addressType) continue;

                    String streetNumber = addressComponent(match, "street_number");
                    String returnedRoute = addressComponent(match, "route");
                    if (streetNumber == null || returnedRoute == null
                            || !houseNumbersMatch(requestedHouseNumber, streetNumber)
                            || (requestedRoute != null && !streetsMatch(requestedRoute, returnedRoute))
                            || (requestedHouseNumber != null && requestedRoute == null)
                            || !localityMatches(match, localityHint)) continue;

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
                logFailure(attempt, "provider_not_retried", httpStatus, status, null);
                throw new IllegalStateException("Geocoding failed; check API access and quota");
            }
            return null;
        }
        throw new IllegalStateException("Geocoding service is unavailable");
    }

    private static boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void waitBeforeRetry(int failedAttempt) {
        long delayMillis = failedAttempt == 1 ? FIRST_RETRY_DELAY_MILLIS : SECOND_RETRY_DELAY_MILLIS;
        try {
            TimeUnit.MILLISECONDS.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Geocoding interrupted");
        }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException)
                && failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static void logFailure(int attempt, String category, Integer httpStatus, String providerStatus,
                                   Throwable cause) {
        String safeProviderStatus = providerStatus == null ? "none" : safeGoogleStatus(providerStatus);
        String causeType = cause == null ? "none" : cause.getClass().getSimpleName();
        LOGGER.warn("Geocoding attempt failed category={} attempt={}/{} httpStatus={} providerStatus={} causeType={}",
                category, attempt, MAX_ATTEMPTS, httpStatus == null ? "none" : httpStatus, safeProviderStatus, causeType);
    }

    private static String safeGoogleStatus(String status) {
        return switch (status) {
            case "OK" -> "OK";
            case "ZERO_RESULTS" -> "ZERO_RESULTS";
            case "OVER_QUERY_LIMIT" -> "OVER_QUERY_LIMIT";
            case "OVER_DAILY_LIMIT" -> "OVER_DAILY_LIMIT";
            case "REQUEST_DENIED" -> "REQUEST_DENIED";
            case "INVALID_REQUEST" -> "INVALID_REQUEST";
            case "UNKNOWN_ERROR" -> "UNKNOWN_ERROR";
            default -> "OTHER";
        };
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

    private static List<String> addressVariants(String address) {
        List<String> variants = new ArrayList<>(3);
        variants.add(address);

        String withoutComplex = withoutIntermediateComplexSegment(address);
        if (!withoutComplex.equals(address)) variants.add(withoutComplex);

        // Try every unit-preserving form before omitting a unit. This retains the
        // specific apartment query whenever the provider can resolve it.
        String leastSpecific = withoutSecondaryUnit(variants.getLast());
        if (!leastSpecific.equals(variants.getLast()) && !variants.contains(leastSpecific)) {
            variants.add(leastSpecific);
        }
        return List.copyOf(variants);
    }

    private static String withoutIntermediateComplexSegment(String address) {
        List<String> segments = commaSegments(address);
        if (segments.size() < 3) return address;

        List<String> localitySegments = localitySegments(segments.subList(1, segments.size()));
        // A single locality may be the user's actual city. Only remove a
        // marked complex segment when a separate final locality remains.
        if (localitySegments.size() != 2
                || !EXPLICIT_COMPLEX_MARKER.matcher(localitySegments.getFirst()).find()) return address;

        List<String> withoutIntermediate = new ArrayList<>(segments.size() - 1);
        withoutIntermediate.add(segments.getFirst());
        withoutIntermediate.addAll(segments.subList(2, segments.size()));
        return String.join(", ", withoutIntermediate);
    }

    private static List<String> commaSegments(String address) {
        List<String> segments = new ArrayList<>();
        for (String segment : address.split(",")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) segments.add(trimmed);
        }
        return segments;
    }

    private static List<String> localitySegments(List<String> segments) {
        List<String> localities = new ArrayList<>(segments);
        if (localities.isEmpty()) return localities;

        int last = localities.size() - 1;
        String lastSegment = localities.get(last);
        Matcher postalCode = TRAILING_POSTAL_CODE.matcher(lastSegment);
        if (postalCode.matches()) {
            lastSegment = postalCode.group(1).trim();
            if (lastSegment.isEmpty()) {
                localities.remove(last);
                last--;
                if (last < 0) return localities;
                lastSegment = localities.get(last);
            } else {
                localities.set(last, lastSegment);
            }
        }

        StateSuffix state = stripTrailingState(lastSegment);
        if (state != null) {
            if (state.prefix().isEmpty()) localities.remove(last);
            else localities.set(last, state.prefix());
        }

        while (!localities.isEmpty() && localities.getLast().matches("\\d{5}(?:-\\d{4})?")) {
            localities.removeLast();
        }
        return localities;
    }

    private static AddressParts addressParts(String address) {
        List<String> segments = commaSegments(address);
        if (segments.size() > 1) {
            String streetSegment = AddressExtractor.normalize(segments.getFirst());
            Matcher houseNumber = HOUSE_NUMBER.matcher(streetSegment);
            if (!houseNumber.find()) return new AddressParts(null, new LocalityHint(null, null));

            String tail = streetSegment.substring(houseNumber.end()).trim();
            UnitBoundary unit = unitBoundary(tail);
            String route = unit == null ? tail : tail.substring(0, unit.start()).trim();
            return new AddressParts(emptyToNull(route), commaLocalityHint(segments));
        }
        return flatAddressParts(address);
    }

    private static AddressParts flatAddressParts(String address) {
        String normalized = AddressExtractor.normalize(address);
        Matcher houseNumber = HOUSE_NUMBER.matcher(normalized);
        if (!houseNumber.find()) return new AddressParts(null, new LocalityHint(null, null));

        String tail = normalized.substring(houseNumber.end()).trim();
        UnitBoundary unit = unitBoundary(tail);
        if (unit != null) {
            String route = tail.substring(0, unit.start()).trim();
            String locality = tail.substring(unit.end()).trim();
            return new AddressParts(emptyToNull(route), flatLocalityHint(locality));
        }

        String[] tokens = tail.split("\\s+");
        List<AddressParts> candidates = new ArrayList<>();
        for (int end = 1; end <= tokens.length; end++) {
            // Require a street name as well as a suffix: MEADOWS is itself a
            // recognized suffix, but in "MEADOWS BOULEVARD" BOULEVARD closes the route.
            if (end < 2) continue;
            String route = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, end));
            if (!AddressExtractor.endsWithStreetType(route)) continue;

            int localityStart = end;
            if (localityStart < tokens.length && CARDINAL_DIRECTIONS.contains(tokens[localityStart])) {
                route += " " + tokens[localityStart++];
            }
            String locality = String.join(" ", java.util.Arrays.copyOfRange(tokens, localityStart, tokens.length));
            candidates.add(new AddressParts(route, flatLocalityHint(locality)));
        }

        // A flattened string can contain a street-type word in its locality.
        // Reject ambiguous splits instead of validating a guessed street route.
        return candidates.size() == 1 ? candidates.getFirst()
                : new AddressParts(null, new LocalityHint(null, null));
    }

    private static UnitBoundary unitBoundary(String tail) {
        Matcher unit = UNIT_MARKER_WITH_VALUE.matcher(tail);
        UnitBoundary fallback = null;
        while (unit.find()) {
            String route = tail.substring(0, unit.start()).trim();
            if (route.isEmpty()) continue;
            UnitBoundary candidate = new UnitBoundary(unit.start(), unit.end());
            if (AddressExtractor.endsWithStreetType(route)) return candidate;
            if (fallback == null) fallback = candidate;
        }
        // Keep a road name such as "UNIT 5 ROAD" intact when UNIT is part of
        // the name rather than a secondary-unit marker.
        return AddressExtractor.endsWithStreetType(tail) ? null : fallback;
    }

    private static LocalityHint commaLocalityHint(List<String> segments) {
        if (segments.size() < 2) return new LocalityHint(null, null);
        List<String> localitySegments = segments.subList(1, segments.size());
        List<String> localities = localitySegments(localitySegments);
        String city = localities.isEmpty() ? null : localities.getLast();
        return new LocalityHint(city, trailingState(localitySegments));
    }

    private static LocalityHint flatLocalityHint(String locality) {
        if (locality == null || locality.isBlank()) return new LocalityHint(null, null);
        String normalized = AddressExtractor.normalize(locality);
        Matcher postalCode = TRAILING_POSTAL_CODE.matcher(normalized);
        if (postalCode.matches()) normalized = postalCode.group(1).trim();
        StateSuffix state = stripTrailingState(normalized);
        if (state != null) normalized = state.prefix();
        return new LocalityHint(emptyToNull(normalized), state == null ? null : state.code());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trailingState(List<String> segments) {
        if (segments.isEmpty()) return null;
        List<String> remaining = new ArrayList<>(segments);
        int last = remaining.size() - 1;
        String lastSegment = remaining.get(last);
        Matcher postalCode = TRAILING_POSTAL_CODE.matcher(lastSegment);
        if (postalCode.matches()) {
            lastSegment = postalCode.group(1).trim();
            if (lastSegment.isEmpty()) {
                remaining.remove(last);
                if (remaining.isEmpty()) return null;
                lastSegment = remaining.getLast();
            }
        }
        StateSuffix state = stripTrailingState(lastSegment);
        return state == null ? null : state.code();
    }

    private static StateSuffix stripTrailingState(String text) {
        String normalized = AddressExtractor.normalize(text);
        if (normalized.isBlank()) return null;
        String[] tokens = normalized.split("\\s+");
        for (int tokenCount = Math.min(4, tokens.length); tokenCount >= 1; tokenCount--) {
            int stateStart = tokens.length - tokenCount;
            String stateText = String.join(" ", java.util.Arrays.copyOfRange(tokens, stateStart, tokens.length));
            String code = US_STATE_CODES_BY_NAME.get(stateText);
            if (code == null && tokenCount == 1 && US_STATE_CODES.contains(stateText)) code = stateText;
            if (code != null) {
                String prefix = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, stateStart));
                return new StateSuffix(prefix, code);
            }
        }
        return null;
    }

    private static boolean localityMatches(JsonNode match, LocalityHint hint) {
        if (hint.city() != null) {
            String returnedCity = firstAddressComponent(match, "locality", "postal_town",
                    "sublocality", "sublocality_level_1", "administrative_area_level_3");
            if (returnedCity == null || !canonicalLocality(hint.city()).equals(canonicalLocality(returnedCity))) {
                return false;
            }
        }
        if (hint.state() != null) {
            String returnedState = addressComponentShortName(match, "administrative_area_level_1");
            if (returnedState == null || !hint.state().equalsIgnoreCase(returnedState)) return false;
        }
        return true;
    }

    private static String firstAddressComponent(JsonNode result, String... types) {
        for (String type : types) {
            String value = addressComponent(result, type);
            if (value != null) return value;
        }
        return null;
    }

    private static String canonicalLocality(String locality) {
        return locality.toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    private static boolean streetsMatch(String requestedRoute, String returnedRoute) {
        return canonicalStreet(requestedRoute).equals(canonicalStreet(returnedRoute));
    }

    private static String canonicalStreet(String route) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ROUTE_TOKEN.matcher(route.toUpperCase(Locale.ROOT));
        while (matcher.find()) {
            String token = matcher.group().replace('’', '\'');
            token = switch (token) {
                case "NORTH" -> "N";
                case "SOUTH" -> "S";
                case "EAST" -> "E";
                case "WEST" -> "W";
                default -> token;
            };
            tokens.add(token);
        }
        if (!tokens.isEmpty()) {
            int suffixIndex = tokens.size() - 1;
            if (suffixIndex > 0 && CARDINAL_DIRECTIONS.contains(tokens.get(suffixIndex))) suffixIndex--;
            tokens.set(suffixIndex, canonicalStreetSuffix(tokens.get(suffixIndex)));
        }
        return String.join(" ", tokens);
    }

    private static String canonicalStreetSuffix(String suffix) {
        return switch (suffix) {
            case "ST" -> "STREET";
            case "RD" -> "ROAD";
            case "AVE", "AV" -> "AVENUE";
            case "BLVD" -> "BOULEVARD";
            case "DR" -> "DRIVE";
            case "LN" -> "LANE";
            case "CT" -> "COURT";
            case "CIR" -> "CIRCLE";
            case "PL" -> "PLACE";
            case "PKWY" -> "PARKWAY";
            case "HWY" -> "HIGHWAY";
            case "TER" -> "TERRACE";
            case "TRL" -> "TRAIL";
            case "SQ" -> "SQUARE";
            case "RTE" -> "ROUTE";
            default -> suffix;
        };
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

    private static String addressComponentShortName(JsonNode result, String type) {
        for (JsonNode component : result.path("address_components")) {
            if (hasType(component, type)) {
                String name = component.path("short_name").asString(null);
                if (name != null && !name.isBlank()) return name;
            }
        }
        return addressComponent(result, type);
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

    private static final class TransientTransportFailure extends RuntimeException {
        private TransientTransportFailure(Throwable cause) {
            super(cause);
        }
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

    private record LocalityHint(String city, String state) { }

    private record AddressParts(String route, LocalityHint localityHint) { }

    private record UnitBoundary(int start, int end) { }

    private record StateSuffix(String prefix, String code) { }
}
