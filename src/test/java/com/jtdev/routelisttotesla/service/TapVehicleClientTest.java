package com.jtdev.routelisttotesla.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;

class TapVehicleClientTest {
    private static final String VIN = "5YJ3E1EA7JF000000";
    private static final String SUBJECT = "tap_subject_123";
    private static final String OTHER_SUBJECT = "tap_subject_other";
    private static final String KEY = "route_123456789012345678901234567890";
    private static final String DELEGATED_TOKEN = "user-delegated-token";
    private static final TapAccessService.GrantIdentity IDENTITY = new TapAccessService.GrantIdentity(
            SUBJECT, "54a78af224dd589f4476b3d72cd686d3bd9035440e4bb19fcf819f3f4d118905");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sendsOrderedWaypointsWithTheLiveDelegatedToken() throws Exception {
        AtomicReference<String> profile = new AtomicReference<>(profile(SUBJECT, "routelist"));
        AtomicReference<String> response = new AtomicReference<>("{\"state\":\"COMPLETED\",\"accepted\":true}");
        AtomicReference<String> routeBody = new AtomicReference<>();
        AtomicInteger sessionCalls = new AtomicInteger();
        AtomicInteger commandCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/api/v1/delegation/session", exchange -> {
            sessionCalls.incrementAndGet();
            assertEquals("Bearer " + DELEGATED_TOKEN, exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, profile.get());
        });
        server.createContext("/api/v1/hub/commands", exchange -> {
            commandCalls.incrementAndGet();
            assertEquals(DELEGATED_TOKEN, exchange.getRequestHeaders().getFirst("X-TAP-Client-Key"));
            assertEquals(KEY, exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            routeBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, response.get());
        });
        server.start();
        try {
            TapVehicleClient client = client(server, authorizedClients(SUBJECT, DELEGATED_TOKEN));
            List<PlaceCandidate> route = List.of(place("first", 42.1), place("second", 42.2));

            assertTrue(client.sendRoute(IDENTITY, VIN, route, KEY).accepted());
            JsonNode waypoints = JSON.readTree(routeBody.get()).path("waypoints");
            assertEquals("first", waypoints.get(0).path("placeId").asString());
            assertEquals(42.2, waypoints.get(1).path("latitude").asDouble());

            response.set("{\"state\":\"UNKNOWN\",\"accepted\":true}");
            assertNull(client.sendRoute(IDENTITY, VIN, route, KEY).accepted());
            assertEquals(2, commandCalls.get());

            assertThrows(IllegalArgumentException.class,
                    () -> client.sendRoute(IDENTITY, VIN, java.util.Collections.nCopies(9, route.getFirst()), KEY));
            profile.set(profile(SUBJECT, "telemetry"));
            assertThrows(AccessDeniedException.class, () -> client.requireAccess(IDENTITY));
            assertEquals(2, commandCalls.get());
            assertEquals(3, sessionCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingOrOtherSubjectDelegationCannotReachTheHub() throws Exception {
        AtomicReference<String> profile = new AtomicReference<>(profile(OTHER_SUBJECT, "routelist"));
        AtomicInteger commandCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/api/v1/delegation/session", exchange -> respond(exchange, 200, profile.get()));
        server.createContext("/api/v1/hub/commands", exchange -> {
            commandCalls.incrementAndGet();
            respond(exchange, 200, "{\"state\":\"COMPLETED\",\"accepted\":true}");
        });
        server.start();
        try {
            OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
            TapVehicleClient client = client(server, clients);
            List<PlaceCandidate> route = List.of(place("first", 42.1));

            assertThrows(AccessDeniedException.class, () -> client.sendRoute(IDENTITY, VIN, route, KEY));
            when(clients.loadAuthorizedClient("tap", SUBJECT))
                    .thenReturn(authorizedClient(SUBJECT, DELEGATED_TOKEN));
            assertThrows(AccessDeniedException.class, () -> client.requireAccess(IDENTITY));
            assertEquals(0, commandCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void telemetryUsesDelegatedAuthorizationAndAmbiguousCommandIsNotRetried() throws Exception {
        AtomicInteger commands = new AtomicInteger();
        AtomicReference<String> telemetryAuthorization = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/api/v1/delegation/session",
                exchange -> respond(exchange, 200, profile(SUBJECT, "routelist")));
        server.createContext("/api/v1/hub/telemetry", exchange -> {
            telemetryAuthorization.set(exchange.getRequestHeaders().getFirst("X-TAP-Client-Key"));
            respond(exchange, 200, """
                    {"events":[],"nextCursor":7,"hasMore":false,"gap":true,
                     "serverTime":"2026-09-26T12:00:00Z","snapshot":{
                       "vin":"%s","cursor":7,"observedAt":"2026-09-26T11:59:00Z",
                       "payload":{"latitude":42.1,"longitude":-83.1}}}
                    """.formatted(VIN));
        });
        server.createContext("/api/v1/hub/commands", exchange -> {
            commands.incrementAndGet();
            respond(exchange, 503, "unavailable");
        });
        server.start();
        try {
            TapVehicleClient client = client(server, authorizedClients(SUBJECT, DELEGATED_TOKEN));
            TapVehicleClient.TelemetryPage page = client.telemetry(IDENTITY, VIN, 7L);
            assertTrue(page.gap());
            assertEquals(7, page.nextCursor());
            assertEquals(DELEGATED_TOKEN, telemetryAuthorization.get());

            assertThrows(IllegalStateException.class,
                    () -> client.sendRoute(IDENTITY, VIN, List.of(place("first", 42.1)), KEY));
            assertEquals(1, commands.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void grantReplacementWaitsForInFlightHubRequestAndInvalidatesOldIdentity() throws Exception {
        CountDownLatch hubRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseHubResponse = new CountDownLatch(1);
        CountDownLatch saveAttempted = new CountDownLatch(1);
        CountDownLatch saveCompleted = new CountDownLatch(1);
        AtomicInteger commandCalls = new AtomicInteger();
        HttpServer server = server();
        server.createContext("/api/v1/delegation/session",
                exchange -> respond(exchange, 200, profile(SUBJECT, "routelist")));
        server.createContext("/api/v1/hub/commands", exchange -> {
            commandCalls.incrementAndGet();
            hubRequestStarted.countDown();
            try {
                if (!releaseHubResponse.await(20, TimeUnit.SECONDS)) {
                    respond(exchange, 504, "{\"state\":\"UNKNOWN\",\"accepted\":null}");
                    return;
                }
                respond(exchange, 200, "{\"state\":\"COMPLETED\",\"accepted\":true}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        });
        server.start();

        SynchronizedAuthorizedClientService clients = new SynchronizedAuthorizedClientService();
        clients.saveAuthorizedClient(authorizedClient(SUBJECT, DELEGATED_TOKEN), null);
        TapVehicleClient client = client(server, clients);
        java.util.concurrent.ExecutorService dispatchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
        Thread replacementSaver = new Thread(() -> {
            saveAttempted.countDown();
            clients.saveAuthorizedClient(authorizedClient(SUBJECT, "replacement-token"), null);
            saveCompleted.countDown();
        }, "tap-grant-replacement");
        replacementSaver.setDaemon(true);

        try {
            java.util.concurrent.Future<Boolean> dispatch = dispatchExecutor.submit(() -> client.sendRoute(
                    IDENTITY, VIN, List.of(place("first", 42.1)), KEY).accepted());
            if (!hubRequestStarted.await(5, TimeUnit.SECONDS)) {
                try {
                    dispatch.get(1, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new AssertionError("A's route request failed before reaching the local hub", e.getCause());
                }
                fail("A's hub request should reach the local server");
            }

            replacementSaver.start();
            assertTrue(saveAttempted.await(5, TimeUnit.SECONDS), "B's grant save should be attempted");
            assertTrue(waitForBlockedState(replacementSaver, 5, TimeUnit.SECONDS),
                    "B's synchronized grant save should wait while A's request holds the grant lock");
            assertEquals(1, saveCompleted.getCount(), "B must not replace A while A's request is in flight");

            releaseHubResponse.countDown();
            assertTrue(dispatch.get(5, TimeUnit.SECONDS), "A's already-started request should complete");
            assertTrue(saveCompleted.await(5, TimeUnit.SECONDS), "B's replacement should complete after A releases the lock");
            replacementSaver.join(5_000);

            assertThrows(AccessDeniedException.class,
                    () -> client.sendRoute(IDENTITY, VIN, List.of(place("second", 42.2)), KEY));
            assertEquals(1, commandCalls.get(), "A's stale identity must not dispatch after B replaces the grant");
        } finally {
            releaseHubResponse.countDown();
            replacementSaver.join(5_000);
            dispatchExecutor.shutdownNow();
            server.stop(0);
        }
    }

    private static boolean waitForBlockedState(Thread thread, long timeout, TimeUnit unit)
            throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            if (thread.getState() == Thread.State.BLOCKED) return true;
            Thread.yield();
        }
        return thread.getState() == Thread.State.BLOCKED;
    }

    private static final class SynchronizedAuthorizedClientService implements OAuth2AuthorizedClientService {
        private OAuth2AuthorizedClient current;

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String registrationId,
                                                                                        String principalName) {
            if (current == null || !current.getClientRegistration().getRegistrationId().equals(registrationId)
                    || !current.getPrincipalName().equals(principalName)) {
                return null;
            }
            return (T) current;
        }

        @Override
        public synchronized void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient,
                                                       Authentication principal) {
            current = authorizedClient;
        }

        @Override
        public synchronized void removeAuthorizedClient(String registrationId, String principalName) {
            if (current != null && current.getClientRegistration().getRegistrationId().equals(registrationId)
                    && current.getPrincipalName().equals(principalName)) {
                current = null;
            }
        }
    }

    private static TapVehicleClient client(HttpServer server, OAuth2AuthorizedClientService clients) {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
        return new TapVehicleClient(JSON, baseUrl, new TapAccessService(JSON, clients, baseUrl));
    }

    private static OAuth2AuthorizedClientService authorizedClients(String subject, String token) {
        OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
        when(clients.loadAuthorizedClient("tap", subject)).thenReturn(authorizedClient(subject, token));
        return clients;
    }

    private static OAuth2AuthorizedClient authorizedClient(String subject, String token) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("tap")
                .clientId("test-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost/login/oauth2/code/tap")
                .authorizationUri("http://localhost/auth/delegate")
                .tokenUri("http://localhost/delegation/token")
                .userInfoUri("http://localhost/delegation/session")
                .userNameAttributeName("sub")
                .clientName("TAP")
                .build();
        return new OAuth2AuthorizedClient(registration, subject,
                new OAuth2AccessToken(BEARER, token, Instant.now().minusSeconds(1),
                        Instant.now().plusSeconds(60)));
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String profile(String subject, String entitlement) {
        return "{\"sub\":\"" + subject + "\",\"email\":\"driver@example.test\",\"entitlements\":[\""
                + entitlement + "\"]}";
    }

    private static PlaceCandidate place(String id, double latitude) {
        return new PlaceCandidate(id, id, "test.png", 0, latitude, -83.1, id);
    }
}
