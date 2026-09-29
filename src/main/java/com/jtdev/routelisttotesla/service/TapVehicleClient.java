package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** TAP owns Tesla credentials, vehicle grants and command deduplication. */
@Service
public class TapVehicleClient {
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final TapAccessService access;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public TapVehicleClient(ObjectMapper mapper,
                            @Value("${tap.base-url}") String baseUrl,
                            TapAccessService access) {
        this.mapper = mapper;
        this.access = access;
        this.baseUri = TapAccessService.baseUri(baseUrl);
    }

    public void requireAccess(TapAccessService.GrantIdentity identity) {
        access.requireAccess(identity);
    }

    public List<Vehicle> vehicles(TapAccessService.GrantIdentity identity) {
        JsonNode body = request(identity, "hub/vehicles", null, null);
        if (!body.isArray()) throw new IllegalStateException("Invalid TAP vehicle response");
        List<Vehicle> result = new ArrayList<>();
        for (JsonNode v : body) {
            String vin = v.path("vin").asString("");
            if (vin.matches("[A-Za-z0-9]{17}")) result.add(new Vehicle(vin,
                    v.path("displayName").asString(vin), v.path("read").asBoolean(false),
                    v.path("command").asBoolean(false)));
        }
        return List.copyOf(result);
    }

    public TelemetryPage telemetry(TapAccessService.GrantIdentity identity, String vin, Long afterCursor) {
        validateVin(vin);
        if (afterCursor != null && afterCursor < 0) throw new IllegalArgumentException("Invalid telemetry cursor");
        String path = "hub/telemetry?vin=" + URLEncoder.encode(vin, StandardCharsets.UTF_8);
        if (afterCursor != null) path += "&after=" + afterCursor + "&limit=100";
        JsonNode body = request(identity, path, null, null);
        if (!body.path("events").isArray() || !body.path("nextCursor").isIntegralNumber()
                || !body.path("nextCursor").canConvertToLong() || body.path("nextCursor").longValue() < 0
                || !body.path("hasMore").isBoolean() || !body.path("gap").isBoolean()) {
            throw new IllegalStateException("Invalid TAP telemetry envelope");
        }
        List<Telemetry> events = new ArrayList<>();
        for (JsonNode event : body.path("events")) events.add(parseTelemetry(event, vin));
        Telemetry snapshot = body.path("snapshot").isObject() ? parseTelemetry(body.path("snapshot"), vin) : null;
        return new TelemetryPage(List.copyOf(events), snapshot, body.path("nextCursor").longValue(),
                body.path("hasMore").booleanValue(), body.path("gap").booleanValue(), instant(body, "serverTime"));
    }

    public CommandResult sendRoute(TapAccessService.GrantIdentity identity, String vin,
                                   List<PlaceCandidate> candidates, String idempotencyKey) {
        validateVin(vin);
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("A stable route command idempotency key is required");
        }
        if (candidates == null || candidates.isEmpty() || candidates.size() > 8) {
            throw new IllegalArgumentException("A Tesla route must contain 1 to 8 reviewed waypoints");
        }
        for (PlaceCandidate candidate : candidates) {
            if (candidate == null || candidate.text() == null || candidate.text().isBlank()
                    || !Double.isFinite(candidate.lat()) || !Double.isFinite(candidate.lon())
                    || Math.abs(candidate.lat()) > 90 || Math.abs(candidate.lon()) > 180
                    || (candidate.lat() == 0 && candidate.lon() == 0)
                    || candidate.pid() == null || !candidate.pid().matches("[A-Za-z0-9_-]{1,512}")) {
                throw new IllegalArgumentException("Every waypoint must have reviewed, valid coordinates");
            }
        }
        List<Map<String, Object>> waypoints = candidates.stream()
                .map(c -> Map.<String, Object>of("latitude", c.lat(), "longitude", c.lon(), "placeId", c.pid())).toList();
        JsonNode body = request(identity, "hub/commands", Map.of("vin", vin,
                "type", "navigation_waypoints", "waypoints", waypoints), idempotencyKey);
        String state = body.path("state").asString("UNKNOWN");
        Boolean accepted = "COMPLETED".equals(state) && body.path("accepted").isBoolean()
                && body.path("accepted").booleanValue() ? Boolean.TRUE
                : "REJECTED".equals(state) && body.path("accepted").isBoolean()
                && !body.path("accepted").booleanValue() ? Boolean.FALSE : null;
        return new CommandResult(state, accepted, Boolean.TRUE.equals(accepted) ? "Tesla accepted the route"
                : "Route was not confirmed by TAP (" + (state.matches("[A-Z_]{1,32}") ? state : "UNKNOWN") + ")");
    }

    private JsonNode request(TapAccessService.GrantIdentity identity, String path, Object body, String key) {
        return access.withToken(identity, userToken -> requestWithToken(userToken, path, body, key));
    }

    private JsonNode requestWithToken(String userToken, String path, Object body, String key) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(Duration.ofSeconds(35)).header("X-TAP-Client-Key", userToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", "RouteListToTesla/0.0.4");
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            // A transport error after POST may mean Tesla received it. Never retry here.
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new AccessDeniedException("TAP denied this vehicle operation");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TAP returned HTTP " + response.statusCode()
                        + (body == null ? "" : "; command outcome is unconfirmed, do not resend automatically"));
            }
            JsonNode parsed = mapper.readTree(response.body());
            if (parsed == null) throw new IllegalStateException("Empty TAP response");
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TAP request interrupted; command outcome may be unconfirmed");
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("TAP request failed; command outcome may be unconfirmed");
        }
    }

    private Telemetry parseTelemetry(JsonNode event, String vin) {
        JsonNode payload = event.path("payload");
        if (!vin.equalsIgnoreCase(event.path("vin").asString("")) || !payload.isObject()
                || !event.path("cursor").isIntegralNumber() || !event.path("cursor").canConvertToLong()
                || event.path("cursor").longValue() < 0) {
            throw new IllegalStateException("Invalid TAP vehicle telemetry");
        }
        return new Telemetry(event.path("cursor").longValue(), instant(event, "observedAt"),
                instant(event, "receivedAt"), number(payload, "latitude"), number(payload, "longitude"),
                payload.path("shiftState").isString() ? payload.path("shiftState").stringValue() : null,
                number(payload, "speedMph"), instant(payload, "locationSourceObservedAt"),
                instant(payload, "shiftStateObservedAt"), instant(payload, "speedObservedAt"));
    }

    private static Double number(JsonNode node, String key) {
        return node.path(key).isNumber() && Double.isFinite(node.path(key).doubleValue())
                ? node.path(key).doubleValue() : null;
    }

    private static Instant instant(JsonNode node, String key) {
        try {
            return node.path(key).isString() ? Instant.parse(node.path(key).stringValue()) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void validateVin(String vin) {
        if (vin == null || !vin.matches("[A-Za-z0-9]{17}")) throw new IllegalArgumentException("A valid VIN is required");
    }

    public record Vehicle(String vin, String displayName, boolean read, boolean command) { }
    public record Telemetry(long cursor, Instant observedAt, Instant receivedAt, Double latitude,
                            Double longitude, String shiftState, Double speedMph, Instant locationObservedAt,
                            Instant shiftStateObservedAt, Instant speedObservedAt) { }
    public record TelemetryPage(List<Telemetry> events, Telemetry snapshot, long nextCursor,
                                boolean hasMore, boolean gap, Instant serverTime) { }
    public record CommandResult(String state, Boolean accepted, String message) { }
}
