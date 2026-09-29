package com.jtdev.routelisttotesla.service;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;
import static org.springframework.security.oauth2.client.registration.ClientRegistration.withRegistrationId;

class TapAccessServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUBJECT = "tap_subject_123";
    private static final String OTHER_SUBJECT = "tap_subject_other";

    @Test
    void identityUsesTapSubjectAndKeepsGrantFingerprintOutOfPublicAttributes() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, profile(SUBJECT, "driver@example.test", "routelist"));
        });
        try {
            TapAccessService access = new TapAccessService(JSON, mock(OAuth2AuthorizedClientService.class), baseUrl(server));
            OAuth2User principal = access.identify(token("delegated-token", Instant.now().plusSeconds(60)));
            TapAccessService.GrantIdentity identity = access.grantIdentity(principal);

            assertEquals(SUBJECT, principal.getName());
            assertEquals("driver@example.test", principal.getAttribute("email"));
            assertFalse(principal.getAttributes().containsKey("grantId"));
            assertEquals(SUBJECT, identity.ownerSub());
            assertNotEquals("delegated-token", identity.grantId());
            assertEquals("Bearer delegated-token", authorization.get());
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void loginForGrantBInvalidatesAAndLogoutForACannotRemoveB() throws Exception {
        AtomicReference<OAuth2AuthorizedClient> storedClient = new AtomicReference<>();
        AtomicReference<String> deleteAuthorization = new AtomicReference<>();
        AtomicReference<Boolean> grantPresentAtDelete = new AtomicReference<>(true);
        AtomicInteger getCalls = new AtomicInteger();
        OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
        when(clients.loadAuthorizedClient("tap", SUBJECT)).thenAnswer(invocation -> storedClient.get());
        doAnswer(invocation -> {
            storedClient.set(null);
            return null;
        }).when(clients).removeAuthorizedClient("tap", SUBJECT);
        HttpServer server = server(exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                grantPresentAtDelete.set(storedClient.get() != null);
                respond(exchange, 204, "");
                return;
            }
            getCalls.incrementAndGet();
            respond(exchange, 200, profile(SUBJECT, "driver@example.test", "routelist"));
        });
        try {
            TapAccessService access = new TapAccessService(JSON, clients, baseUrl(server));
            OAuth2User principalA = access.identify(token("grant-a", Instant.now().plusSeconds(300)));
            TapAccessService.GrantIdentity grantA = access.grantIdentity(principalA);
            storedClient.set(authorizedClient("grant-a", Instant.now().plusSeconds(300)));
            access.requireAccess(grantA);
            int callsAfterA = getCalls.get();

            // OAuth login B replaces the single (registration, TAP sub) slot.
            storedClient.set(authorizedClient("grant-b", Instant.now().plusSeconds(300)));
            assertThrows(AccessDeniedException.class, () -> access.requireAccess(grantA));
            assertEquals(callsAfterA, getCalls.get(), "A must fail before its stale token is sent to TAP");
            access.revoke(grantA);
            assertEquals("grant-b", storedClient.get().getAccessToken().getTokenValue());
            assertNull(deleteAuthorization.get(), "A logout must not revoke B's grant");

            OAuth2User principalB = access.identify(token("grant-b", Instant.now().plusSeconds(300)));
            TapAccessService.GrantIdentity grantB = access.grantIdentity(principalB);
            access.requireAccess(grantB);
            access.revoke(grantB);
            assertNull(storedClient.get());
            assertEquals("Bearer grant-b", deleteAuthorization.get());
            assertFalse(grantPresentAtDelete.get(), "the exact local grant is removed before remote revocation");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void expiredStoredGrantIsRejectedWithoutCallingTap() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<OAuth2AuthorizedClient> storedClient = new AtomicReference<>();
        OAuth2AuthorizedClientService clients = mock(OAuth2AuthorizedClientService.class);
        when(clients.loadAuthorizedClient("tap", SUBJECT)).thenAnswer(invocation -> storedClient.get());
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, profile(SUBJECT, "driver@example.test", "routelist"));
        });
        try {
            TapAccessService access = new TapAccessService(JSON, clients, baseUrl(server));
            TapAccessService.GrantIdentity identity = access.grantIdentity(
                    access.identify(token("expired-grant", Instant.now().plusSeconds(60))));
            storedClient.set(authorizedClient("expired-grant", Instant.now().minusSeconds(1)));
            int callsAfterLogin = calls.get();

            assertThrows(AccessDeniedException.class, () -> access.requireAccess(identity));
            assertEquals(callsAfterLogin, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identityRejectsExpiredLoginTokenBeforeCallingTap() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, profile(SUBJECT, "driver@example.test", "routelist"));
        });
        try {
            TapAccessService access = new TapAccessService(JSON, mock(OAuth2AuthorizedClientService.class), baseUrl(server));
            assertThrows(AccessDeniedException.class,
                    () -> access.identify(token("expired-token", Instant.now().minusSeconds(1))));
            assertEquals(0, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void identityRequiresRoutelistEntitlement() throws Exception {
        HttpServer server = server(exchange -> respond(exchange, 200,
                profile(SUBJECT, "driver@example.test", "telemetry")));
        try {
            TapAccessService access = new TapAccessService(JSON, mock(OAuth2AuthorizedClientService.class), baseUrl(server));
            assertThrows(AccessDeniedException.class,
                    () -> access.identify(token("not-entitled", Instant.now().plusSeconds(60))));
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/delegation/session", handler);
        server.start();
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1";
    }

    private static String profile(String subject, String email, String entitlement) {
        return "{\"sub\":\"" + subject + "\",\"email\":\"" + email
                + "\",\"entitlements\":[\"" + entitlement + "\"]}";
    }

    private static OAuth2AccessToken token(String value, Instant expiresAt) {
        return new OAuth2AccessToken(BEARER, value, expiresAt.minusSeconds(60), expiresAt);
    }

    private static OAuth2AuthorizedClient authorizedClient(String value, Instant expiresAt) {
        var registration = withRegistrationId("tap")
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
        return new OAuth2AuthorizedClient(registration, SUBJECT, token(value, expiresAt));
    }
}
