package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.LocationResolution;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.util.AddressExtractor;
import com.jtdev.routelisttotesla.util.StreetSuffixCatalog;
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
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GeocodingClient {
    public static final String GEOCODING_VERSION = "street-address-v9";

    private static final Logger LOGGER = LoggerFactory.getLogger(GeocodingClient.class);
    private static final String CONFLICTING_UNIT = "\u0000";
    private static final int MAX_ATTEMPTS_PER_FORM = 3;
    private static final int MAX_TOTAL_ATTEMPTS_PER_STOP = 8;
    private static final int MAX_QUERY_FORMS_PER_STOP = 6;
    private static final long STOP_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(25);
    private static final long BATCH_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final long REQUEST_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(15);
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
                    + "((?:[A-Z]?\\d+[A-Z0-9-]*|[A-Z](?:-\\d+[A-Z]?)?))(?=\\s|$)");
    private static final Pattern RETURNED_UNIT_LABEL = Pattern.compile(
            "(?i)^(?:APARTMENT|APT|UNIT|SUITE|STE|ROOM|RM)\\.?\\s+");
    private static final Pattern TRAILING_POSTAL_CODE = Pattern.compile("(?i)^(.*?)(?:\\s+)?(\\d{5}(?:-\\d{4})?)$");
    private static final Set<String> CARDINAL_DIRECTIONS = Set.of("N", "S", "E", "W", "NE", "NW", "SE", "SW",
            "NORTH", "SOUTH", "EAST", "WEST", "NORTHEAST", "NORTHWEST", "SOUTHEAST", "SOUTHWEST");
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
        return batchGeocode(owner, inputs, Long.MAX_VALUE);
    }

    public List<PlaceCandidate> batchGeocode(String owner, List<PlaceCandidate> inputs, long absoluteDeadlineNanos) {
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

        reserveBatch(owner);
        try {
            long batchDeadline = Math.min(absoluteDeadlineNanos, System.nanoTime() + BATCH_DEADLINE_NANOS);
            Map<String, PlaceCandidate> resultsByQuery = new HashMap<>();
            for (Map.Entry<String, PlaceCandidate> query : uniqueQueries.entrySet()) {
                long stopDeadline = Math.min(batchDeadline, System.nanoTime() + STOP_DEADLINE_NANOS);
                resultsByQuery.put(query.getKey(), geocodeOne(owner, query.getValue(), stopDeadline, batchDeadline));
            }
            return inputs.stream().map(candidate -> {
                PlaceCandidate result = resultsByQuery.get(candidate.text());
                return candidate.withLocationFrom(result);
            }).toList();
        } finally {
            release(owner);
        }
    }

    private void reserveBatch(String owner) {
        long now = System.nanoTime();
        synchronized (budgetLock) {
            globalUsage.prune(now);
            ownerUsage.entrySet().removeIf(entry -> {
                entry.getValue().prune(now);
                return entry.getValue().calls == 0 && !activeOwnerBatches.containsKey(entry.getKey());
            });
            int activeForOwner = activeOwnerBatches.getOrDefault(owner, 0);
            if (activeForOwner >= ownerConcurrentBatches || activeGlobalBatches >= globalConcurrentBatches) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Geocoding concurrency limit exceeded; retry later");
            }
            activeOwnerBatches.put(owner, activeForOwner + 1);
            activeGlobalBatches++;
        }
    }

    private void reserveCall(String owner) {
        long now = System.nanoTime();
        synchronized (budgetLock) {
            globalUsage.prune(now);
            Usage account = ownerUsage.computeIfAbsent(owner, ignored -> new Usage());
            account.prune(now);
            if (account.calls >= ownerCallsPerHour || globalUsage.calls >= globalCallsPerHour) {
                throw new CallBudgetExhausted();
            }
            account.reserve(now, 1);
            globalUsage.reserve(now, 1);
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

    private HttpResponse<String> sendWithGlobalPacing(String owner, URI uri, long deadline, RecoveryState state) {
        CompletableFuture<HttpResponse<String>> call;
        synchronized (pacingLock) {
            long now = System.nanoTime();
            long waitNanos = pacingStarted ? nextRequestAtNanos - now : 0;
            if (pacingStarted && waitNanos > 0) {
                if (deadline - now <= waitNanos) throw new DeadlineExceeded();
                try {
                    TimeUnit.NANOSECONDS.sleep(waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Geocoding interrupted");
                }
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) throw new DeadlineExceeded();
            if (state.attempts >= MAX_TOTAL_ATTEMPTS_PER_STOP) throw new CallBudgetExhausted();
            reserveCall(owner);
            state.attempts++;
            Duration timeout = Duration.ofNanos(Math.min(REQUEST_TIMEOUT_NANOS, remainingNanos));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
            call = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            nextRequestAtNanos = System.nanoTime() + minimumDelayNanos;
            pacingStarted = true;
        }
        try {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                call.cancel(true);
                throw new DeadlineExceeded();
            }
            return call.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            call.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Geocoding interrupted");
        } catch (TimeoutException e) {
            call.cancel(true);
            throw new DeadlineExceeded();
        } catch (ExecutionException e) {
            Throwable cause = unwrap(e);
            if (cause instanceof IOException) throw new TransientTransportFailure(cause);
            throw new IllegalStateException("Geocoding service is unavailable");
        }
    }

    private PlaceCandidate geocodeOne(String owner, PlaceCandidate candidate, long stopDeadline, long batchDeadline) {
        RecoveryState state = new RecoveryState();
        ExpectedAddress expected = expectedAddress(candidate.text());
        if (expected.houseNumber() == null || expected.route() == null
                || !hasStreetSuffix(expected.route())) {
            return outcome(candidate, null, LocationResolution.Status.NO_MATCH, "original",
                    "address_not_specific", state, null);
        }

        List<LookupForm> forms = addressVariants(candidate.text(), expected);
        Map<String, CandidateMatch> buildingMatches = new LinkedHashMap<>();
        for (LookupForm form : forms) {
            if (form.unitlessFallback() && !buildingMatches.isEmpty()) break;
            if (deadlineReached(stopDeadline, batchDeadline)) {
                return outcome(candidate, null, LocationResolution.Status.DEFERRED, form.strategy(),
                        "deadline_exhausted", state, 5);
            }
            LookupOutcome lookup;
            try {
                lookup = lookup(owner, candidate, expected, form, stopDeadline, batchDeadline, state);
            } catch (DeadlineExceeded | CallBudgetExhausted e) {
                String reason = e instanceof DeadlineExceeded ? "deadline_exhausted" : "request_budget_exhausted";
                return outcome(candidate, null, LocationResolution.Status.DEFERRED, form.strategy(), reason, state, 30);
            } catch (ProviderLookupFailure e) {
                LocationResolution.Status status = e.deferred
                        ? LocationResolution.Status.DEFERRED : LocationResolution.Status.LOOKUP_UNAVAILABLE;
                return outcome(candidate, null, status, form.strategy(), e.reasonCode, state, e.retryAfterSeconds);
            }
            if (lookup.status() == LookupStatus.AMBIGUOUS) {
                return outcome(candidate, null, LocationResolution.Status.AMBIGUOUS, form.strategy(),
                        "multiple_destinations", state, null);
            }
            if (lookup.status() == LookupStatus.UNIT_CONFLICT) {
                return outcome(candidate, null, LocationResolution.Status.NO_MATCH, form.strategy(),
                        "unit_conflict", state, null);
            }
            if (lookup.status() == LookupStatus.UNIT_MATCH) {
                return outcome(candidate, lookup.candidate(), LocationResolution.Status.VERIFIED_UNIT,
                        lookup.strategy() == null ? form.strategy() : lookup.strategy(),
                        "exact_unit_match", state, null, lookup.matchedAddress());
            }
            if (lookup.status() == LookupStatus.BUILDING_MATCH) {
                PlaceCandidate match = lookup.candidate();
                if (expected.unit() == null) {
                    return outcome(candidate, match, LocationResolution.Status.VERIFIED_BUILDING,
                            lookup.strategy() == null ? form.strategy() : lookup.strategy(),
                            "exact_street_match", state, null, lookup.matchedAddress());
                }
                buildingMatches.putIfAbsent(destinationKey(match), new CandidateMatch(match,
                        lookup.strategy() == null ? form.strategy() : lookup.strategy(), lookup.matchedAddress()));
            }
        }
        if (buildingMatches.size() > 1) {
            return outcome(candidate, null, LocationResolution.Status.AMBIGUOUS, "unit_preserving",
                    "multiple_destinations", state, null);
        }
        if (buildingMatches.size() == 1) {
            CandidateMatch match = buildingMatches.values().iterator().next();
            return outcome(candidate, match.candidate(), LocationResolution.Status.VERIFIED_BUILDING,
                    match.strategy(), "unit_not_confirmed", state, null, match.matchedAddress());
        }
        return outcome(candidate, null, LocationResolution.Status.NO_MATCH, "recovery_exhausted", "no_safe_match", state, null);
    }

    private LookupOutcome lookup(String owner, PlaceCandidate candidate, ExpectedAddress expected, LookupForm form,
                                 long stopDeadline, long batchDeadline, RecoveryState state) {
        return lookup(owner, candidate, expected, form, stopDeadline, batchDeadline, state, null);
    }

    private LookupOutcome lookup(String owner, PlaceCandidate candidate, ExpectedAddress expected, LookupForm form,
                                 long stopDeadline, long batchDeadline, RecoveryState state, ProofTarget proof) {
        URI uri = URI.create(geocodeUrl + (form.reverse() ? "?latlng=" : "?address=") + encode(form.address())
                + (form.components() == null ? "" : "&components=" + encode(form.components()))
                + "&region=" + encode(regionBias) + "&key=" + encode(apiKey));
        for (int attempt = 1; attempt <= MAX_ATTEMPTS_PER_FORM; attempt++) {
            if (state.attempts >= MAX_TOTAL_ATTEMPTS_PER_STOP) throw new CallBudgetExhausted();
            long deadline = Math.min(stopDeadline, batchDeadline);
            if (attempt > 1) waitBeforeRetry(attempt - 1, deadline);

            HttpResponse<String> response;
            try {
                response = sendWithGlobalPacing(owner, uri, deadline, state);
            } catch (TransientTransportFailure e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                logFailure(attempt, "transport", null, null, cause);
                if (attempt == MAX_ATTEMPTS_PER_FORM) {
                    throw new ProviderLookupFailure("provider_unavailable", false, null);
                }
                continue;
            }

            int httpStatus = response.statusCode();
            if (httpStatus == 429) {
                logFailure(attempt, "http_quota", httpStatus, null, null);
                throw new ProviderLookupFailure("provider_quota", true, 60);
            }
            if (httpStatus != 200) {
                if (isRetryableHttpStatus(httpStatus)) {
                    logFailure(attempt, "http_transient", httpStatus, null, null);
                    if (attempt == MAX_ATTEMPTS_PER_FORM) {
                        throw new ProviderLookupFailure("provider_unavailable", false, null);
                    }
                    continue;
                }
                logFailure(attempt, "http_not_retried", httpStatus, null, null);
                throw new ProviderLookupFailure("provider_rejected", false, null);
            }

            JsonNode body;
            try {
                body = mapper.readTree(response.body());
            } catch (JacksonException e) {
                logFailure(attempt, "invalid_response", httpStatus, null, e);
                throw new ProviderLookupFailure("invalid_provider_response", false, null);
            }
            if (body == null) {
                logFailure(attempt, "invalid_response", httpStatus, null, null);
                throw new ProviderLookupFailure("invalid_provider_response", false, null);
            }
            String status = body.path("status").asString();
            if ("UNKNOWN_ERROR".equals(status)) {
                logFailure(attempt, "provider_transient", httpStatus, status, null);
                if (attempt == MAX_ATTEMPTS_PER_FORM) {
                    throw new ProviderLookupFailure("provider_unavailable", false, null);
                }
                continue;
            }
            if ("OVER_QUERY_LIMIT".equals(status) || "OVER_DAILY_LIMIT".equals(status)) {
                logFailure(attempt, "provider_quota", httpStatus, status, null);
                throw new ProviderLookupFailure("provider_quota", true, 60);
            }
            if ("OK".equals(status) && body.path("results").isArray()) {
                Map<String, PlaceCandidate> unitMatches = new LinkedHashMap<>();
                Map<String, PlaceCandidate> buildingMatches = new LinkedHashMap<>();
                Set<String> postalUnitMatches = new HashSet<>();
                Set<String> postalBuildingMatches = new HashSet<>();
                Map<String, JsonNode> recoveryCandidates = new LinkedHashMap<>();
                boolean unitConflict = false;
                // Preserve an existing original-city proof before trying the
                // postal fallback. Counting both representations as separate
                // destinations would turn a valid v7 lookup into ambiguity.
                boolean originalCityInterpolation = proof != null && proof.postal() != null
                        && hasOriginalCityInterpolation(body.path("results"), expected, proof);
                for (JsonNode match : body.path("results")) {
                    boolean addressType = hasType(match, "street_address") || hasType(match, "premise")
                            || hasType(match, "subpremise");
                    if (match.path("partial_match").asBoolean(false) || !addressType) continue;

                    List<String> streetNumbers = componentValues(match, "street_number");
                    if (streetNumbers.isEmpty() || streetNumbers.stream()
                            .anyMatch(number -> !expected.houseNumber().equals(canonicalHouseNumber(number)))) continue;
                    boolean streetMatches = streetsMatch(expected.route(), match);
                    boolean cityMatches = localityMatches(match, expected.localityHint());
                    boolean postalCityMatches = proof != null && proof.postal() != null
                            && proof.postal().matches(match);
                    // A different municipality must not hide another rooftop in
                    // the requested postal area. This classifies competition only;
                    // accepting a destination still requires the original proof.
                    boolean postalCompetitor = proof != null && proof.postal() != null && streetMatches
                            && unambiguousStreet(match, expected.route())
                            && postalProof(match, expected, candidate.text()) != null;
                    if (proof != null && proof.postal() != null
                            && (cityMatches || postalCityMatches || postalCompetitor) && streetMatches
                            && match.path("geometry").path("location_type").asString("").equals("ROOFTOP")) {
                        JsonNode point = match.path("geometry").path("location");
                        double lat = point.path("lat").asDouble(Double.NaN), lon = point.path("lng").asDouble(Double.NaN);
                        String id = match.path("place_id").asString("");
                        if (point.path("lat").isNumber() && point.path("lng").isNumber()
                                && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                                && !(lat == 0 && lon == 0) && id.matches("[A-Za-z0-9_-]{1,512}")) {
                            if (!proof.placeId().equals(id)
                                    || distanceMetres(proof.latitude(), proof.longitude(), lat, lon) > 2) {
                                return new LookupOutcome(LookupStatus.AMBIGUOUS, null);
                            }
                            String unit = returnedUnit(match);
                            if (postalCompetitor && unit != null && (CONFLICTING_UNIT.equals(unit)
                                    || expected.unit() == null || !expected.unit().equals(canonicalUnit(unit)))) {
                                unitConflict = true;
                            }
                        }
                    }
                    if (proof != null && proof.rejectCompetingDestinations()
                            && localityMatches(match, expected.localityHint())
                            && componentValues(match, "route").stream().anyMatch(route -> sameStreetName(expected.route(), route))) {
                        JsonNode point = match.path("geometry").path("location");
                        double lat = point.path("lat").asDouble(Double.NaN), lon = point.path("lng").asDouble(Double.NaN);
                        String id = match.path("place_id").asString("");
                        if (point.path("lat").isNumber() && point.path("lng").isNumber()
                                && Double.isFinite(lat) && Double.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180
                                && !(lat == 0 && lon == 0) && id.matches("[A-Za-z0-9_-]{1,512}")
                                && (!proof.placeId().equals(id)
                                    || distanceMetres(proof.latitude(), proof.longitude(), lat, lon) > 2)) {
                            return new LookupOutcome(LookupStatus.AMBIGUOUS, null);
                        }
                    }
                    if (!streetMatches || !cityMatches && !postalCityMatches) {
                        if (proof == null && form.strategy().equals("original")
                                && recoverableRepresentation(match, expected, streetMatches, cityMatches)) {
                            String unit = returnedUnit(match);
                            if (unit != null && (CONFLICTING_UNIT.equals(unit)
                                    || expected.unit() == null || !expected.unit().equals(unit))) {
                                unitConflict = true;
                            } else {
                                recoveryCandidates.putIfAbsent(match.path("place_id").asString("") + ":"
                                        + match.path("geometry").path("location"), match);
                            }
                        }
                        continue;
                    }
                    if (!cityMatches && postalCityMatches && !unambiguousStreet(match, expected.route())) continue;
                    if (proof != null && !proof.accepts(match,
                            match.path("geometry").path("location").path("lat").asDouble(Double.NaN),
                            match.path("geometry").path("location").path("lng").asDouble(Double.NaN),
                            match.path("place_id").asString(""), !cityMatches && postalCityMatches)) continue;

                    String returnedUnit = returnedUnit(match);
                    if (CONFLICTING_UNIT.equals(returnedUnit)) {
                        unitConflict = true;
                        continue;
                    }
                    if (expected.unit() == null ? returnedUnit != null
                            : returnedUnit != null && !expected.unit().equals(canonicalUnit(returnedUnit))) {
                        unitConflict = true;
                        continue;
                    }
                    if (originalCityInterpolation && !cityMatches && postalCityMatches) continue;

                    JsonNode location = match.path("geometry").path("location");
                    if (!location.path("lat").isNumber() || !location.path("lng").isNumber()) continue;
                    double latitude = location.path("lat").asDouble();
                    double longitude = location.path("lng").asDouble();
                    String placeId = match.path("place_id").asString(null);
                    if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                            || !Double.isFinite(longitude) || longitude < -180 || longitude > 180
                            || (latitude == 0 && longitude == 0) || placeId == null
                            || !placeId.matches("[A-Za-z0-9_-]{1,512}")) continue;

                    PlaceCandidate resolved = candidate.withLatLonPid(latitude, longitude, placeId);
                    String destination = destinationKey(resolved);
                    if (expected.unit() != null && returnedUnit != null) {
                        if (!cityMatches && !unitMatches.containsKey(destination)) postalUnitMatches.add(destination);
                        if (cityMatches) postalUnitMatches.remove(destination);
                        unitMatches.putIfAbsent(destination, resolved);
                    } else {
                        if (!cityMatches && !buildingMatches.containsKey(destination)) postalBuildingMatches.add(destination);
                        if (cityMatches) postalBuildingMatches.remove(destination);
                        buildingMatches.putIfAbsent(destination, resolved);
                    }
                }
                if (unitConflict && proof != null && proof.postal() != null) {
                    return new LookupOutcome(LookupStatus.UNIT_CONFLICT, null);
                }
                if (unitMatches.size() > 1) return new LookupOutcome(LookupStatus.AMBIGUOUS, null);
                if (unitMatches.size() == 1) {
                    PlaceCandidate match = unitMatches.values().iterator().next();
                    return new LookupOutcome(LookupStatus.UNIT_MATCH, match,
                            postalUnitMatches.contains(destinationKey(match)) ? "postal_locality_proof" : null, null);
                }
                if (unitConflict) return new LookupOutcome(LookupStatus.UNIT_CONFLICT, null);
                if (buildingMatches.size() > 1) return new LookupOutcome(LookupStatus.AMBIGUOUS, null);
                if (buildingMatches.size() == 1) {
                    PlaceCandidate match = buildingMatches.values().iterator().next();
                    return new LookupOutcome(LookupStatus.BUILDING_MATCH, match,
                            postalBuildingMatches.contains(destinationKey(match)) ? "postal_locality_proof" : null, null);
                }
                if (recoveryCandidates.size() > 1) return new LookupOutcome(LookupStatus.AMBIGUOUS, null);
                if (recoveryCandidates.size() == 1) {
                    return corroborateRepresentation(owner, candidate, expected,
                            recoveryCandidates.values().iterator().next(), stopDeadline, batchDeadline, state);
                }
            } else if (!"ZERO_RESULTS".equals(status)) {
                logFailure(attempt, "provider_not_retried", httpStatus, status, null);
                throw new ProviderLookupFailure("provider_rejected", false, null);
            }
            return new LookupOutcome(LookupStatus.NO_MATCH, null);
        }
        return new LookupOutcome(LookupStatus.NO_MATCH, null);
    }

    private static boolean recoverableRepresentation(JsonNode match, ExpectedAddress expected,
                                                      boolean streetMatches, boolean cityMatches) {
        LocalityHint hint = expected.localityHint();
        if (hint == null || hint.city() == null || hint.state() == null || hint.country() == null
                || !localityMatches(match, new LocalityHint(null, hint.state(), hint.country()))
                || !match.path("geometry").path("location_type").asString("").equals("ROOFTOP")) return false;
        JsonNode point = match.path("geometry").path("location");
        double lat = point.path("lat").asDouble(Double.NaN), lon = point.path("lng").asDouble(Double.NaN);
        if (!point.path("lat").isNumber() || !point.path("lng").isNumber()
                || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                || lat == 0 && lon == 0
                || !match.path("place_id").asString("").matches("[A-Za-z0-9_-]{1,512}")) return false;
        List<String> routes = componentValues(match, "route");
        if (routes.isEmpty() || routes.stream().anyMatch(route -> !sameStreetName(expected.route(), route))) return false;
        for (String type : List.of("route", "locality", "postal_town", "sublocality", "sublocality_level_1",
                "administrative_area_level_3")) {
            int count = 0;
            for (JsonNode component : match.path("address_components")) if (hasType(component, type)) count++;
            if (count > 1) return false;
        }
        List<String> firstRoute = canonicalStreetWords(routes.getFirst());
        String firstSuffix = firstRoute.get(suffixIndex(firstRoute));
        for (String route : routes) {
            List<String> words = canonicalStreetWords(route);
            if (!StreetSuffixCatalog.equivalent(firstSuffix, words.get(suffixIndex(words)))) return false;
        }
        // Never repair the street representation and locality in the same step.
        return streetMatches != cityMatches;
    }

    private static boolean hasOriginalCityInterpolation(JsonNode results, ExpectedAddress expected, ProofTarget proof) {
        for (JsonNode match : results) {
            if (match.path("partial_match").asBoolean(false)
                    || !(hasType(match, "street_address") || hasType(match, "premise") || hasType(match, "subpremise"))
                    || !match.path("geometry").path("location_type").asString("").equals("RANGE_INTERPOLATED")) continue;
            List<String> numbers = componentValues(match, "street_number");
            if (numbers.isEmpty() || numbers.stream().anyMatch(number -> !expected.houseNumber().equals(canonicalHouseNumber(number)))
                    || !streetsMatch(expected.route(), match) || !localityMatches(match, expected.localityHint())) continue;
            JsonNode point = match.path("geometry").path("location");
            if (proof.accepts(match, point.path("lat").asDouble(Double.NaN), point.path("lng").asDouble(Double.NaN),
                    match.path("place_id").asString(""), false)) return true;
        }
        return false;
    }

    private LookupOutcome corroborateRepresentation(String owner, PlaceCandidate candidate, ExpectedAddress expected,
                                                     JsonNode proposed, long stopDeadline, long batchDeadline,
                                                     RecoveryState state) {
        double lat = proposed.path("geometry").path("location").path("lat").asDouble();
        double lon = proposed.path("geometry").path("location").path("lng").asDouble();
        String id = proposed.path("place_id").asString();
        boolean localityAlias = streetsMatch(expected.route(), proposed);
        boolean unitConfirmed = true;
        ExpectedAddress proofExpected = expected;
        LookupForm proofForm;
        if (localityAlias) {
            proofForm = new LookupForm(lat + "," + lon, null, "reverse_locality_proof", false, true);
        } else {
            String route = addressComponent(proposed, "route");
            proofExpected = new ExpectedAddress(expected.houseNumber(), route, expected.unit(), expected.localityHint());
            // Constrain the unmodified OCR street first. A canonical query alone
            // would only prove that the proposed replacement maps to itself.
            String originalStreet = expected.houseNumber() + " " + expected.route()
                    + (expected.unit() == null ? "" : " UNIT " + expected.unit());
            LookupOutcome originalProof = lookup(owner, candidate, proofExpected,
                    new LookupForm(originalStreet, structuredComponents(expected.localityHint()), "original_street_proof", false),
                    stopDeadline, batchDeadline, state, new ProofTarget(lat, lon, id, false, true));
            if (originalProof.status() != LookupStatus.UNIT_MATCH && originalProof.status() != LookupStatus.BUILDING_MATCH) {
                return originalProof;
            }
            unitConfirmed = originalProof.status() == LookupStatus.UNIT_MATCH;
            String address = expected.houseNumber() + " " + route
                    + (expected.unit() == null ? "" : " UNIT " + expected.unit());
            proofForm = new LookupForm(address, structuredComponents(expected.localityHint()), "street_representation_proof", false);
        }
        LookupOutcome corroboration = lookup(owner, candidate, proofExpected, proofForm, stopDeadline, batchDeadline,
                state, new ProofTarget(lat, lon, id, localityAlias, !localityAlias,
                        localityAlias ? postalProof(proposed, expected, candidate.text()) : null));
        if (corroboration.status() == LookupStatus.UNIT_CONFLICT || corroboration.status() == LookupStatus.AMBIGUOUS) {
            return corroboration;
        }
        if (corroboration.status() != LookupStatus.UNIT_MATCH && corroboration.status() != LookupStatus.BUILDING_MATCH) {
            return new LookupOutcome(LookupStatus.NO_MATCH, null);
        }
        String returnedUnit = returnedUnit(proposed);
        LookupStatus status = unitConfirmed && corroboration.status() == LookupStatus.UNIT_MATCH
                && expected.unit() != null && expected.unit().equals(returnedUnit)
                ? LookupStatus.UNIT_MATCH : LookupStatus.BUILDING_MATCH;
        String display = proposed.path("formatted_address").asString(null);
        if (display != null && display.length() > 512) display = null;
        return new LookupOutcome(status, candidate.withLatLonPid(lat, lon, id),
                "postal_locality_proof".equals(corroboration.strategy()) ? "corroborated_postal_locality"
                        : localityAlias ? "corroborated_locality" : "corroborated_street_representation", display);
    }

    private static PostalProof postalProof(JsonNode proposed, ExpectedAddress expected, String originalText) {
        LocalityHint hint = expected.localityHint();
        if (hint == null || !"US".equals(hint.country())) return null;
        String zip = singlePostalCode(proposed);
        if (zip == null) return null;
        // A supplied ZIP is a constraint, never silently replaced by the proposal.
        String normalized = AddressExtractor.normalize(originalText).replace(',', ' ').trim();
        CountrySuffix country = stripTrailingCountry(normalized);
        if (country != null) normalized = country.prefix();
        Matcher suppliedZip = TRAILING_POSTAL_CODE.matcher(normalized);
        if (suppliedZip.matches() && !suppliedZip.group(2).substring(0, 5).equals(zip)) return null;
        String suffix = suppliedZip.matches() && suppliedZip.group(2).contains("-")
                ? suppliedZip.group(2).substring(6) : null;
        if (suffix != null && !suffix.equals(singlePostalSuffix(proposed))) return null;
        JsonNode point = proposed.path("geometry").path("location");
        if (!PostalLocalityCatalog.supports(zip, hint.city(), hint.state(),
                point.path("lat").asDouble(Double.NaN), point.path("lng").asDouble(Double.NaN))) return null;
        LocalityComponent city = singleLocalityComponent(proposed);
        if (city == null || city.name().isBlank()) return null;
        LocalityHint canonical = new LocalityHint(city.name(), hint.state(), hint.country());
        return localityMatches(proposed, canonical) ? new PostalProof(canonical, city.type(), zip, suffix) : null;
    }

    private static String singlePostalCode(JsonNode result) {
        int components = 0;
        for (JsonNode component : result.path("address_components")) if (hasType(component, "postal_code")) components++;
        List<String> codes = componentValues(result, "postal_code");
        if (components != 1 || codes.isEmpty() || codes.stream().anyMatch(code -> !code.matches("[0-9]{5}"))) return null;
        return codes.stream().allMatch(codes.getFirst()::equals) ? codes.getFirst() : null;
    }

    private static boolean unambiguousStreet(JsonNode result, String expectedRoute) {
        int components = 0;
        for (JsonNode component : result.path("address_components")) if (hasType(component, "route")) components++;
        List<String> routes = componentValues(result, "route");
        return components == 1 && !routes.isEmpty()
                && routes.stream().allMatch(route -> streetRoutesMatch(expectedRoute, route));
    }

    private static String singlePostalSuffix(JsonNode result) {
        int components = 0;
        for (JsonNode component : result.path("address_components")) if (hasType(component, "postal_code_suffix")) components++;
        List<String> suffixes = componentValues(result, "postal_code_suffix");
        if (components != 1 || suffixes.isEmpty() || suffixes.stream().anyMatch(value -> !value.matches("[0-9]{4}"))) return null;
        return suffixes.stream().allMatch(suffixes.getFirst()::equals) ? suffixes.getFirst() : null;
    }

    private static LocalityComponent singleLocalityComponent(JsonNode result) {
        // Unincorporated postal communities may be represented only by a township
        // at administrative level 3, with a different neighborhood inside it.
        // This name is used only by the ZIP-backed, same-rooftop postal proof;
        // it does not relax direct township matching or use a neighborhood/county.
        // A present primary locality must remain authoritative, including conflicts.
        for (String type : List.of("locality", "postal_town", "sublocality", "sublocality_level_1",
                "administrative_area_level_3")) {
            int components = 0;
            for (JsonNode component : result.path("address_components")) if (hasType(component, type)) components++;
            if (components == 0) continue;
            List<String> names = componentValues(result, type);
            if (components != 1 || names.isEmpty()) return null;
            String canonical = canonicalLocalityComponent(type, names.getFirst());
            return !canonical.isEmpty() && names.stream().allMatch(name -> canonical.equals(canonicalLocalityComponent(type, name)))
                    ? new LocalityComponent(names.getFirst(), type) : null;
        }
        return null;
    }

    private static String canonicalLocalityComponent(String type, String name) {
        // Google's long/short township labels may use Township, Twp or the bare
        // base name. Reuse the existing label parser only at its typed level.
        String township = type.equals("administrative_area_level_3") ? canonicalTownshipName(name) : null;
        return township == null ? canonicalLocality(name) : township;
    }

    private static boolean sameStreetName(String requested, String returned) {
        List<String> left = canonicalStreetWords(requested), right = canonicalStreetWords(returned);
        int a = suffixIndex(left), b = suffixIndex(right);
        if (a < 1 || b < 1 || !StreetSuffixCatalog.isKnown(left.get(a))
                || !StreetSuffixCatalog.isKnown(right.get(b))) return false;
        boolean leftDirection = CARDINAL_DIRECTIONS.contains(left.getFirst());
        boolean rightDirection = CARDINAL_DIRECTIONS.contains(right.getFirst());
        if (leftDirection != rightDirection || leftDirection && !left.getFirst().equals(right.getFirst())
                || !left.subList(a + 1, left.size()).equals(right.subList(b + 1, right.size()))) return false;
        String leftName = String.join("", left.subList(leftDirection ? 1 : 0, a));
        String rightName = String.join("", right.subList(rightDirection ? 1 : 0, b));
        return !leftName.isEmpty() && leftName.equals(rightName);
    }

    private static double distanceMetres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double h = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 6_371_000 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
    }

    private static boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private static void waitBeforeRetry(int failedAttempt, long deadline) {
        long delayMillis = failedAttempt == 1 ? FIRST_RETRY_DELAY_MILLIS : SECOND_RETRY_DELAY_MILLIS;
        long delayNanos = TimeUnit.MILLISECONDS.toNanos(delayMillis);
        if (deadline - System.nanoTime() <= delayNanos) throw new DeadlineExceeded();
        try {
            TimeUnit.NANOSECONDS.sleep(delayNanos);
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
                category, attempt, MAX_ATTEMPTS_PER_FORM, httpStatus == null ? "none" : httpStatus,
                safeProviderStatus, causeType);
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

    private static PlaceCandidate outcome(PlaceCandidate original, PlaceCandidate location,
                                          LocationResolution.Status status, String strategy, String reasonCode,
                                          RecoveryState state, Integer retryAfterSeconds) {
        return outcome(original, location, status, strategy, reasonCode, state, retryAfterSeconds, null);
    }

    private static PlaceCandidate outcome(PlaceCandidate original, PlaceCandidate location,
                                          LocationResolution.Status status, String strategy, String reasonCode,
                                          RecoveryState state, Integer retryAfterSeconds, String matchedAddress) {
        PlaceCandidate result = location == null
                ? original.withLatLonPid(0, 0, null)
                : original.withLatLonPid(location.lat(), location.lon(), location.pid());
        return result.withResolution(new LocationResolution(status, strategy, reasonCode, state.attempts,
                GEOCODING_VERSION, retryAfterSeconds, matchedAddress));
    }

    private static boolean deadlineReached(long stopDeadline, long batchDeadline) {
        return Math.min(stopDeadline, batchDeadline) - System.nanoTime() <= 0;
    }

    private static String destinationKey(PlaceCandidate candidate) {
        return Math.round(candidate.lat() * 1_000_000d) + ":" + Math.round(candidate.lon() * 1_000_000d);
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

    private static List<LookupForm> addressVariants(String address, ExpectedAddress expected) {
        Map<String, LookupForm> forms = new LinkedHashMap<>();
        String withoutComplex = withoutIntermediateComplexSegment(address);
        addForm(forms, new LookupForm(address, null, "original", false));
        if (!withoutComplex.equals(address)) {
            addForm(forms, new LookupForm(withoutComplex, null, "complex_cleanup", false));
        }

        String normalizedUnit = normalizeUnitFormatting(withoutComplex);
        if (!normalizedUnit.equals(withoutComplex)) {
            addForm(forms, new LookupForm(normalizedUnit, null, "unit_format", false));
        }

        String components = structuredComponents(expected.localityHint());
        int reservedForms = (components == null ? 0 : 1) + (expected.unit() == null ? 0 : 1);
        for (String spelling : suffixQueryAlternatives(normalizedUnit, expected.route())) {
            if (forms.size() >= MAX_QUERY_FORMS_PER_STOP - reservedForms) break;
            addForm(forms, new LookupForm(spelling, null, "street_suffix", false));
        }
        if (components != null) {
            addForm(forms, new LookupForm(withoutComplex, components, "structured_locality", false));
        }

        if (expected.unit() != null) {
            String cleanedWithoutUnit = withoutSecondaryUnit(withoutComplex);
            if (!cleanedWithoutUnit.equals(withoutComplex)) {
                addForm(forms, new LookupForm(cleanedWithoutUnit, null, "unitless_fallback", true));
            }
        }
        return List.copyOf(forms.values().stream().limit(MAX_QUERY_FORMS_PER_STOP).toList());
    }

    private static List<String> suffixQueryAlternatives(String address, String route) {
        List<String> routeWords = routeWords(route);
        int suffixIndex = suffixIndex(routeWords);
        if (suffixIndex < 0) return List.of();
        String suffix = routeWords.get(suffixIndex);
        if (!StreetSuffixCatalog.isKnown(suffix)) return List.of();
        Matcher house = HOUSE_NUMBER.matcher(address);
        if (!house.find()) return List.of();
        Matcher words = ROUTE_TOKEN.matcher(address);
        words.region(house.end(), address.length());
        for (int index = 0; index <= suffixIndex; index++) if (!words.find()) return List.of();
        if (!suffix.equalsIgnoreCase(words.group())) return List.of();
        int suffixStart = words.start();
        int suffixEnd = words.end();
        if (suffixEnd < address.length() && address.charAt(suffixEnd) == '.') suffixEnd++;
        List<String> alternatives = new ArrayList<>();
        for (String spelling : StreetSuffixCatalog.preferredSpellings(suffix)) {
            if (!suffix.equalsIgnoreCase(spelling)) {
                alternatives.add(address.substring(0, suffixStart) + spelling + address.substring(suffixEnd));
            }
        }
        return List.copyOf(alternatives);
    }

    private static void addForm(Map<String, LookupForm> forms, LookupForm form) {
        String key = form.address() + "\u0000" + (form.components() == null ? "" : form.components());
        if (forms.size() < MAX_QUERY_FORMS_PER_STOP) forms.putIfAbsent(key, form);
    }

    private static String structuredComponents(LocalityHint hint) {
        if (hint == null || (hint.city() == null && hint.state() == null && hint.country() == null)) return null;
        List<String> components = new ArrayList<>(3);
        if (hint.city() != null) components.add("locality:" + hint.city());
        if (hint.state() != null) components.add("administrative_area:" + hint.state());
        if (hint.country() != null) components.add("country:" + hint.country());
        return String.join("|", components);
    }

    private static String normalizeUnitFormatting(String address) {
        UnitSpan unit = unitSpan(address);
        if (unit == null) return address;
        String prefix = address.substring(0, unit.start()).replaceAll("[,\\s]+$", "").trim();
        String suffix = address.substring(unit.end()).replaceAll("^\\s+", "");
        String marker = address.substring(unit.start(), unit.end()).replaceFirst("^[,\\s]+", "")
                .replaceFirst("(?i)[.#\\s]*" + Pattern.quote(unit.value()) + "$", "")
                .replaceAll("[.#\\s]+$", "").trim();
        if (marker.isBlank()) marker = "#";
        StringBuilder normalized = new StringBuilder(prefix);
        if (!prefix.isEmpty()) normalized.append(' ');
        // Formatting a suite, room, or floor must not turn it into an apartment.
        normalized.append(marker.toUpperCase(Locale.ROOT)).append(' ').append(unit.value());
        if (!suffix.isEmpty()) {
            if (suffix.startsWith(",")) normalized.append(suffix.replaceFirst("^,\\s*", ", "));
            else normalized.append(", ").append(suffix);
        }
        return normalized.toString().replaceAll("\\s+", " ").replaceAll(",\\s*,+", ", ").trim();
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

        CountrySuffix country = stripTrailingCountry(lastSegment);
        if (country != null) {
            if (country.prefix().isEmpty()) {
                localities.remove(last);
                if (localities.isEmpty()) return localities;
                last = localities.size() - 1;
                lastSegment = localities.get(last);
            } else {
                localities.set(last, country.prefix());
                lastSegment = country.prefix();
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
            if (!houseNumber.find()) return new AddressParts(null, new LocalityHint(null, null, null));

            String tail = streetSegment.substring(houseNumber.end()).trim();
            UnitBoundary unit = unitBoundary(tail);
            String route = unit == null ? tail : tail.substring(0, unit.start()).trim();
            return new AddressParts(emptyToNull(route), commaLocalityHint(segments));
        }
        return flatAddressParts(address);
    }

    private static ExpectedAddress expectedAddress(String address) {
        AddressParts parts = addressParts(address);
        return new ExpectedAddress(houseNumber(address), parts.route(), unitValue(address), parts.localityHint());
    }

    private static AddressParts flatAddressParts(String address) {
        String normalized = AddressExtractor.normalize(address);
        Matcher houseNumber = HOUSE_NUMBER.matcher(normalized);
        if (!houseNumber.find()) return new AddressParts(null, new LocalityHint(null, null, null));

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
                int directionLength = localityStart + 1 < tokens.length
                        && diagonalDirection(tokens[localityStart], tokens[localityStart + 1]) != null ? 2 : 1;
                route += " " + String.join(" ", java.util.Arrays.copyOfRange(tokens, localityStart, localityStart + directionLength));
                localityStart += directionLength;
            }
            String locality = String.join(" ", java.util.Arrays.copyOfRange(tokens, localityStart, tokens.length));
            candidates.add(new AddressParts(route, flatLocalityHint(locality)));
        }

        // A flattened string can contain a street-type word in its locality.
        // Reject ambiguous splits instead of validating a guessed street route.
        return candidates.size() == 1 ? candidates.getFirst()
                : new AddressParts(null, new LocalityHint(null, null, null));
    }

    private static boolean hasStreetSuffix(String route) {
        if (AddressExtractor.endsWithStreetType(route)) return true;
        List<String> words = routeWords(route);
        int suffix = suffixIndex(words);
        return suffix >= 0 && suffix < words.size() - 1
                && AddressExtractor.endsWithStreetType(String.join(" ", words.subList(0, suffix + 1)));
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
        if (segments.size() < 2) return new LocalityHint(null, null, null);
        List<String> remaining = new ArrayList<>(segments.subList(1, segments.size()));
        removeTrailingPostalCode(remaining);
        String country = removeTrailingCountry(remaining);
        String state = removeTrailingState(remaining);
        if (country == null && state != null) country = "US";
        String city = remaining.isEmpty() ? null : remaining.getLast();
        return new LocalityHint(city, state, country);
    }

    private static LocalityHint flatLocalityHint(String locality) {
        if (locality == null || locality.isBlank()) return new LocalityHint(null, null, null);
        String normalized = AddressExtractor.normalize(locality);
        Matcher postalCode = TRAILING_POSTAL_CODE.matcher(normalized);
        if (postalCode.matches()) normalized = postalCode.group(1).trim();
        CountrySuffix countrySuffix = stripTrailingCountry(normalized);
        String country = countrySuffix == null ? null : countrySuffix.code();
        if (countrySuffix != null) normalized = countrySuffix.prefix();
        StateSuffix state = stripTrailingState(normalized);
        if (state != null) normalized = state.prefix();
        if (country == null && state != null) country = "US";
        return new LocalityHint(emptyToNull(normalized), state == null ? null : state.code(), country);
    }

    private static String unitValue(String address) {
        UnitSpan unit = unitSpan(address);
        return unit == null ? null : unit.value();
    }

    private static UnitSpan unitSpan(String address) {
        List<String> segments = commaSegments(address);
        if (segments.isEmpty()) return null;
        String first = segments.getFirst();
        int firstOffset = address.indexOf(first);
        Matcher houseNumber = HOUSE_NUMBER.matcher(first);
        if (houseNumber.find()) {
            String tail = first.substring(houseNumber.end());
            UnitBoundary boundary = unitBoundary(tail);
            if (boundary != null) {
                Matcher unit = UNIT_MARKER_WITH_VALUE.matcher(tail);
                if (unit.find(boundary.start())) {
                    return new UnitSpan(firstOffset + houseNumber.end() + unit.start(),
                            firstOffset + houseNumber.end() + unit.end(), canonicalUnit(unit.group(1)));
                }
            }
        }
        for (int index = 1; index < segments.size(); index++) {
            String segment = segments.get(index);
            Matcher unit = UNIT_MARKER_WITH_VALUE.matcher(segment);
            if (unit.find() && segment.substring(0, unit.start()).isBlank()
                    && segment.substring(unit.end()).isBlank()) {
                int offset = address.indexOf(segment, firstOffset + first.length());
                return new UnitSpan(offset + unit.start(), offset + unit.end(), canonicalUnit(unit.group(1)));
            }
        }
        return null;
    }

    private static String returnedUnit(JsonNode result) {
        List<String> returned = componentValues(result, "subpremise").stream().map(GeocodingClient::canonicalReturnedUnit)
                .distinct().toList();
        return returned.isEmpty() ? null : returned.size() == 1 ? returned.getFirst() : CONFLICTING_UNIT;
    }

    private static String canonicalUnit(String unit) {
        return unit.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String canonicalReturnedUnit(String unit) {
        // Google can display the same subpremise as "Apartment 208" / "Apt 208".
        // Strip one explicit display label, preserving literal codes such as APT208
        // and leaving unrecognized building/floor labels conflicting.
        String normalized = Normalizer.normalize(unit, Normalizer.Form.NFKC).strip();
        return canonicalUnit(RETURNED_UNIT_LABEL.matcher(normalized).replaceFirst(""));
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void removeTrailingPostalCode(List<String> segments) {
        if (segments.isEmpty()) return;
        int last = segments.size() - 1;
        Matcher postalCode = TRAILING_POSTAL_CODE.matcher(segments.get(last));
        if (!postalCode.matches()) return;
        String prefix = postalCode.group(1).trim();
        if (prefix.isEmpty()) segments.remove(last);
        else segments.set(last, prefix);
    }

    private static String removeTrailingCountry(List<String> segments) {
        if (segments.isEmpty()) return null;
        CountrySuffix country = stripTrailingCountry(segments.getLast());
        if (country == null) return null;
        if (country.prefix().isEmpty()) segments.removeLast();
        else segments.set(segments.size() - 1, country.prefix());
        return country.code();
    }

    private static String removeTrailingState(List<String> segments) {
        if (segments.isEmpty()) return null;
        StateSuffix state = stripTrailingState(segments.getLast());
        if (state == null) return null;
        if (state.prefix().isEmpty()) segments.removeLast();
        else segments.set(segments.size() - 1, state.prefix());
        return state.code();
    }

    private static CountrySuffix stripTrailingCountry(String text) {
        String normalized = AddressExtractor.normalize(text);
        if (normalized.isBlank()) return null;
        String[] tokens = normalized.split("\\s+");
        for (int count = Math.min(4, tokens.length); count >= 1; count--) {
            int start = tokens.length - count;
            String suffix = String.join(" ", java.util.Arrays.copyOfRange(tokens, start, tokens.length));
            String code = canonicalCountry(suffix);
            if (code != null) {
                String prefix = String.join(" ", java.util.Arrays.copyOfRange(tokens, 0, start));
                return new CountrySuffix(prefix, code);
            }
        }
        return null;
    }

    private static String canonicalCountry(String country) {
        String normalized = canonicalLocality(country);
        return switch (normalized) {
            case "US", "USA", "UNITEDSTATES", "UNITEDSTATESOFAMERICA" -> "US";
            case "CANADA" -> "CA";
            case "MEXICO" -> "MX";
            case "UNITEDKINGDOM", "UK", "GREATBRITAIN" -> "GB";
            default -> null;
        };
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
        if (hint == null) return true;
        if (hint.city() != null) {
            String expectedCity = canonicalLocality(hint.city());
            boolean found = false;
            for (String type : List.of("locality", "postal_town", "sublocality", "sublocality_level_1",
                    "administrative_area_level_3")) {
                boolean matchedAtLevel = false;
                boolean differentAtLevel = false;
                for (JsonNode component : match.path("address_components")) {
                    if (!hasType(component, type)) continue;
                    String longName = component.path("long_name").asString("");
                    String shortName = component.path("short_name").asString("");
                    boolean componentMatches = expectedCity.equals(canonicalLocality(longName))
                            || expectedCity.equals(canonicalLocality(shortName));
                    if (type.equals("administrative_area_level_3")
                            && canonicalTownshipName(longName) != null
                            && !expectedCity.equals(canonicalLocality(longName))) {
                        // A shortened township base is allowed only through the
                        // township/strong-locality checks below. An explicit
                        // full township name can still match its typed level.
                        componentMatches = false;
                    }
                    matchedAtLevel |= componentMatches;
                    differentAtLevel |= !componentMatches;
                }
                // A city and its township can differ. Two contradictory values at
                // the same semantic level cannot establish a safe city match.
                if (matchedAtLevel && differentAtLevel) return false;
                found |= matchedAtLevel;
            }
            if (!found && !matchesTownshipWithoutCity(match, hint.city())) return false;
        }
        if (hint.state() != null) {
            List<String> returnedStates = componentValues(match, "administrative_area_level_1");
            if (returnedStates.isEmpty() || returnedStates.stream()
                    .anyMatch(state -> !hint.state().equals(canonicalState(state)))) return false;
        }
        if (hint.country() != null) {
            List<String> returnedCountries = componentValues(match, "country");
            if (returnedCountries.isEmpty() || returnedCountries.stream()
                    .anyMatch(country -> !hint.country().equals(canonicalCountryValue(country)))) return false;
        }
        return true;
    }

    private static boolean matchesTownshipWithoutCity(JsonNode match, String city) {
        // An unincorporated postal community may have no locality/postal_town.
        // Require its same-named township and reject any conflicting neighborhood.
        // When Google omits the neighborhood, require a corroborating short name.
        // Neither shape may override a conflicting city result.
        if (!componentValues(match, "locality").isEmpty() || !componentValues(match, "postal_town").isEmpty()
                || !componentValues(match, "sublocality").isEmpty()
                || !componentValues(match, "sublocality_level_1").isEmpty()) return false;
        String expected = canonicalLocality(city);
        List<String> neighborhoods = componentValues(match, "neighborhood");
        if (neighborhoods.stream()
                .anyMatch(value -> !expected.equals(canonicalLocality(value)))) return false;
        boolean townshipFound = false;
        for (JsonNode component : match.path("address_components")) {
            if (!hasType(component, "administrative_area_level_3")) continue;
            townshipFound = true;
            String longName = component.path("long_name").asString("");
            String shortName = component.path("short_name").asString("");
            if (!expected.equals(canonicalTownshipName(longName))) return false;
            if (neighborhoods.isEmpty() && shortName.isBlank()) return false;
            if (!shortName.isBlank() && !expected.equals(canonicalLocality(shortName))
                    && !expected.equals(canonicalTownshipName(shortName))) return false;
        }
        if (!townshipFound) return false;
        List<String> countries = componentValues(match, "country");
        return !countries.isEmpty() && countries.stream().allMatch(value -> "US".equals(canonicalCountryValue(value)));
    }

    private static String canonicalTownshipName(String value) {
        String normalized = AddressExtractor.normalize(value);
        String name = normalized.replaceFirst("^(?:CHARTER\\s+)?(?:TOWNSHIP|TWP)\\.?\\s+OF\\s+", "")
                .replaceFirst("\\s+(?:CHARTER\\s+)?(?:TOWNSHIP|TWP)\\.?$", "");
        return name.equals(normalized) ? null : canonicalLocality(name);
    }

    private static List<String> localityComponentValues(JsonNode result) {
        List<String> values = new ArrayList<>();
        for (String type : List.of("locality", "postal_town", "sublocality", "sublocality_level_1",
                "administrative_area_level_3")) {
            values.addAll(componentValues(result, type));
        }
        return values;
    }

    private static List<String> componentValues(JsonNode result, String type) {
        List<String> values = new ArrayList<>();
        for (JsonNode component : result.path("address_components")) {
            if (hasType(component, type)) {
                String longName = component.path("long_name").asString(null);
                String shortName = component.path("short_name").asString(null);
                if (longName != null && !longName.isBlank()) values.add(longName);
                if (shortName != null && !shortName.isBlank()) values.add(shortName);
            }
        }
        return values;
    }

    private static String canonicalState(String state) {
        String normalized = AddressExtractor.normalize(state);
        if (US_STATE_CODES_BY_NAME.containsKey(normalized)) return US_STATE_CODES_BY_NAME.get(normalized);
        return normalized;
    }

    private static String canonicalCountryValue(String country) {
        String code = canonicalCountry(country);
        return code == null ? canonicalLocality(country) : code;
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

    private static boolean streetsMatch(String requestedRoute, JsonNode result) {
        List<String> requestedWords = routeWords(requestedRoute);
        int requestedSuffix = suffixIndex(requestedWords);
        if (requestedSuffix < 0) return false;
        for (JsonNode component : result.path("address_components")) {
            if (!hasType(component, "route")) continue;
            String longRoute = component.path("long_name").asString("");
            String shortRoute = component.path("short_name").asString("");
            if (streetRoutesMatch(requestedRoute, longRoute)) return true;
            List<String> longWords = routeWords(longRoute);
            List<String> shortWords = routeWords(shortRoute);
            int longSuffix = suffixIndex(longWords);
            int shortSuffix = suffixIndex(shortWords);
            // A shared abbreviation must not bridge incompatible explicit
            // suffixes (for example PARK versus PARKS).
            if (longSuffix >= 0 && shortSuffix >= 0
                    && StreetSuffixCatalog.equivalent(requestedWords.get(requestedSuffix), longWords.get(longSuffix))
                    && StreetSuffixCatalog.equivalent(longWords.get(longSuffix), shortWords.get(shortSuffix))
                    && streetRoutesMatch(requestedRoute, shortRoute)) return true;
        }
        return false;
    }

    private static boolean streetRoutesMatch(String requested, String returned) {
        List<String> requestedWords = canonicalStreetWords(requested);
        List<String> returnedWords = canonicalStreetWords(returned);
        if (requestedWords.size() != returnedWords.size() || requestedWords.isEmpty()) return false;
        int suffix = suffixIndex(requestedWords);
        if (suffix != suffixIndex(returnedWords) || suffix < 0) return false;
        for (int index = 0; index < requestedWords.size(); index++) {
            if (index == suffix) {
                if (!StreetSuffixCatalog.equivalent(requestedWords.get(index), returnedWords.get(index))) return false;
            } else if (!requestedWords.get(index).equals(returnedWords.get(index))) return false;
        }
        return true;
    }

    private static List<String> routeWords(String route) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = ROUTE_TOKEN.matcher(route.toUpperCase(Locale.ROOT));
        while (matcher.find()) tokens.add(matcher.group().replace('’', '\''));
        return tokens;
    }

    private static List<String> canonicalStreetWords(String route) {
        List<String> tokens = routeWords(route);
        if (tokens.size() > 2 && diagonalDirection(tokens.get(0), tokens.get(1)) != null) {
            tokens.set(0, diagonalDirection(tokens.get(0), tokens.get(1)));
            tokens.remove(1);
        } else if (!tokens.isEmpty() && CARDINAL_DIRECTIONS.contains(tokens.getFirst())) {
            tokens.set(0, canonicalDirection(tokens.getFirst()));
        }
        int last = tokens.size() - 1;
        if (last > 1 && diagonalDirection(tokens.get(last - 1), tokens.get(last)) != null) {
            tokens.set(last - 1, diagonalDirection(tokens.get(last - 1), tokens.get(last)));
            tokens.remove(last);
        } else if (last >= 0 && CARDINAL_DIRECTIONS.contains(tokens.get(last))) {
            tokens.set(last, canonicalDirection(tokens.get(last)));
        }
        // These title abbreviations apply only to the first name token. A
        // terminal ST/MT/FT remains a suffix, with its own catalog identity.
        int nameStart = !tokens.isEmpty() && CARDINAL_DIRECTIONS.contains(tokens.getFirst()) ? 1 : 0;
        if (tokens.size() > nameStart + 1) {
            tokens.set(nameStart, switch (tokens.get(nameStart)) {
                case "ST" -> "SAINT";
                case "MT" -> "MOUNT";
                case "FT" -> "FORT";
                default -> tokens.get(nameStart);
            });
        }
        return tokens;
    }

    private static int suffixIndex(List<String> words) {
        int index = words.size() - 1;
        if (index > 1 && diagonalDirection(words.get(index - 1), words.get(index)) != null) index -= 2;
        else if (index > 0 && CARDINAL_DIRECTIONS.contains(words.get(index))) index--;
        return index;
    }

    private static String canonicalDirection(String token) {
        return switch (token) {
            case "NORTH" -> "N";
            case "SOUTH" -> "S";
            case "EAST" -> "E";
            case "WEST" -> "W";
            case "NORTHEAST" -> "NE";
            case "NORTHWEST" -> "NW";
            case "SOUTHEAST" -> "SE";
            case "SOUTHWEST" -> "SW";
            default -> token;
        };
    }

    private static String diagonalDirection(String first, String second) {
        String northSouth = canonicalDirection(first);
        String eastWest = canonicalDirection(second);
        return (northSouth.equals("N") || northSouth.equals("S"))
                && (eastWest.equals("E") || eastWest.equals("W")) ? northSouth + eastWest : null;
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

    private static final class DeadlineExceeded extends RuntimeException { }

    private static final class CallBudgetExhausted extends RuntimeException { }

    private static final class ProviderLookupFailure extends RuntimeException {
        private final String reasonCode;
        private final boolean deferred;
        private final Integer retryAfterSeconds;

        private ProviderLookupFailure(String reasonCode, boolean deferred, Integer retryAfterSeconds) {
            this.reasonCode = reasonCode;
            this.deferred = deferred;
            this.retryAfterSeconds = retryAfterSeconds;
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

    private static final class RecoveryState {
        private int attempts;
    }

    private enum LookupStatus { NO_MATCH, UNIT_MATCH, UNIT_CONFLICT, BUILDING_MATCH, AMBIGUOUS }

    private record LookupOutcome(LookupStatus status, PlaceCandidate candidate, String strategy, String matchedAddress) {
        private LookupOutcome(LookupStatus status, PlaceCandidate candidate) { this(status, candidate, null, null); }
    }

    private record CandidateMatch(PlaceCandidate candidate, String strategy, String matchedAddress) { }

    private record LookupForm(String address, String components, String strategy, boolean unitlessFallback, boolean reverse) {
        private LookupForm(String address, String components, String strategy, boolean unitlessFallback) {
            this(address, components, strategy, unitlessFallback, false);
        }
    }

    private record LocalityComponent(String name, String type) { }

    private record PostalProof(LocalityHint locality, String localityType, String zip, String suffix) {
        private boolean matches(JsonNode result) {
            LocalityComponent city = singleLocalityComponent(result);
            if (city == null) return false;
            boolean municipal = localityType.equals("administrative_area_level_3");
            if (municipal != city.type().equals("administrative_area_level_3")) return false;
            boolean sameName = municipal
                    ? canonicalLocalityComponent(localityType, locality.city()).equals(canonicalLocalityComponent(city.type(), city.name()))
                    : canonicalLocality(locality.city()).equals(canonicalLocality(city.name()));
            LocalityHint canonical = municipal
                    ? new LocalityHint(city.name(), locality.state(), locality.country()) : locality;
            return sameName && zip.equals(singlePostalCode(result)) && localityMatches(result, canonical)
                    && (suffix == null || suffix.equals(singlePostalSuffix(result)));
        }
    }

    private record ProofTarget(double latitude, double longitude, String placeId, boolean reverse,
                               boolean rejectCompetingDestinations, PostalProof postal) {
        private ProofTarget(double latitude, double longitude, String placeId, boolean reverse,
                            boolean rejectCompetingDestinations) {
            this(latitude, longitude, placeId, reverse, rejectCompetingDestinations, null);
        }

        private boolean accepts(JsonNode match, double lat, double lon, String id, boolean postalMatch) {
            JsonNode location = match.path("geometry").path("location");
            if (!location.path("lat").isNumber() || !location.path("lng").isNumber()
                    || !Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
                    || lat == 0 && lon == 0 || !id.matches("[A-Za-z0-9_-]{1,512}")) return false;
            String type = match.path("geometry").path("location_type").asString("");
            if (postalMatch) {
                return type.equals("ROOFTOP") && placeId.equals(id)
                        && distanceMetres(latitude, longitude, lat, lon) <= 2;
            }
            return (reverse ? Set.of("ROOFTOP", "RANGE_INTERPOLATED").contains(type)
                    : type.equals("ROOFTOP") && placeId.equals(id))
                    && distanceMetres(latitude, longitude, lat, lon) <= (reverse ? 10 : 2);
        }
    }

    private record ExpectedAddress(String houseNumber, String route, String unit, LocalityHint localityHint) { }

    private record LocalityHint(String city, String state, String country) { }

    private record AddressParts(String route, LocalityHint localityHint) { }

    private record UnitBoundary(int start, int end) { }

    private record UnitSpan(int start, int end, String value) { }

    private record StateSuffix(String prefix, String code) { }

    private record CountrySuffix(String prefix, String code) { }
}
