package com.jtdev.routelisttotesla.config;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.service.AutoNavigationService;
import com.jtdev.routelisttotesla.service.TapAccessService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "google.api.key=local-test")
@org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
class SecurityConfigTest {
    private static final String SUBJECT = "tap_subject_123";
    private static final String EMAIL = "driver@example.test";
    private static final String VIN = "5YJ3E1EA7JF000000";
    private static final String CLIENT_KEY = "local-test-client-key";
    private static final String DELEGATED_TOKEN = "local-user-token";
    private static final String CALLBACK = "/login/oauth2/code/tap";
    private static final AtomicReference<String> PROFILE =
            new AtomicReference<>(profile(SUBJECT, EMAIL, "routelist"));
    private static final AtomicReference<String> TOKEN_FORM = new AtomicReference<>();
    private static final AtomicReference<String> TOKEN_CLIENT_KEY = new AtomicReference<>();
    private static final AtomicInteger TOKEN_CALLS = new AtomicInteger();
    private static final AtomicInteger SESSION_CALLS = new AtomicInteger();
    private static final HttpServer TAP = startTap();

    @Autowired MockMvc mvc;
    @Autowired OAuth2AuthorizedClientService clients;
    @Autowired ClientRegistrationRepository registrations;
    @Autowired TapAccessService tapAccess;
    @MockitoBean AutoNavigationService navigation;

    @DynamicPropertySource
    static void tapProperties(DynamicPropertyRegistry properties) {
        String base = "http://127.0.0.1:" + TAP.getAddress().getPort();
        properties.add("TAP_AUTHORIZATION_URI", () -> base + "/auth/delegate");
        properties.add("TAP_BASE_URL", () -> base + "/api/v1");
        properties.add("TAP_CLIENT_ID", () -> "test-client");
        properties.add("TAP_CLIENT_KEY", () -> CLIENT_KEY);
        properties.add("TAP_REDIRECT_URI", () -> "http://localhost" + CALLBACK);
        properties.add("TAP_LEGACY_OWNER_EMAIL", () -> "");
        properties.add("TAP_LEGACY_OWNER_SUB", () -> "");
    }

    @BeforeEach
    void resetFakeTap() {
        PROFILE.set(profile(SUBJECT, EMAIL, "routelist"));
        TOKEN_FORM.set(null);
        TOKEN_CLIENT_KEY.set(null);
        TOKEN_CALLS.set(0);
        SESSION_CALLS.set(0);
        clients.removeAuthorizedClient("tap", SUBJECT);
        clients.removeAuthorizedClient("tap", "expired_subject");
        reset(navigation);
    }

    @AfterAll
    static void stopTap() {
        TAP.stop(0);
    }

    @Test
    void oauthCallbackRequiresStateUsesPkceAndCannotBeReplayed() throws Exception {
        AutoNavSession active = new AutoNavSession("active-session", VIN, SUBJECT,
                List.of(new PlaceCandidate("Stop", "Stop", "route.png", 0, 42.1, -83.1, "stop")));
        when(navigation.getActiveSessionForUser(any(TapAccessService.GrantIdentity.class))).thenReturn(active);

        MvcResult start = mvc.perform(get("/oauth2/authorization/tap"))
                .andExpect(status().is3xxRedirection()).andReturn();
        Map<String, String> authorization = query(start.getResponse().getRedirectedUrl());
        String state = authorization.get("state");
        String challenge = authorization.get("code_challenge");
        assertNotNull(state);
        assertNotNull(challenge);
        assertEquals("S256", authorization.get("code_challenge_method"));
        MockHttpSession loginSession = (MockHttpSession) start.getRequest().getSession(false);
        assertNotNull(loginSession);

        mvc.perform(get(CALLBACK).session(loginSession).param("code", "local-auth-code").param("state", "wrong-state"))
                .andExpect(status().is3xxRedirection());
        assertEquals(0, TOKEN_CALLS.get());

        MvcResult login = mvc.perform(get(CALLBACK).session(loginSession)
                        .param("code", "local-auth-code").param("state", state))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertEquals(1, TOKEN_CALLS.get());
        assertEquals(CLIENT_KEY, TOKEN_CLIENT_KEY.get());
        Map<String, String> tokenRequest = form(TOKEN_FORM.get());
        assertEquals("authorization_code", tokenRequest.get("grant_type"));
        assertEquals("local-auth-code", tokenRequest.get("code"));
        String verifier = tokenRequest.get("code_verifier");
        assertNotNull(verifier);
        String expectedChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.UTF_8)));
        assertEquals(challenge, expectedChallenge);

        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(authenticatedSession);
        SecurityContext securityContext = (SecurityContext) authenticatedSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        OAuth2AuthenticationToken authentication = (OAuth2AuthenticationToken) securityContext.getAuthentication();
        TapAccessService.GrantIdentity grantIdentity = tapAccess.grantIdentity(authentication.getPrincipal());
        assertEquals(SUBJECT, grantIdentity.ownerSub());
        String expectedGrantId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(DELEGATED_TOKEN.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedGrantId, grantIdentity.grantId());
        assertEquals(Set.of("sub", "email", "name"), authentication.getPrincipal().getAttributes().keySet());
        mvc.perform(get("/route/auto-navigate/active")
                        .servletPath("/route/auto-navigate/active").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        verify(navigation).getActiveSessionForUser(grantIdentity);
        verify(navigation, never()).getActiveSessionForUser(argThat(identity ->
                identity != null && EMAIL.equals(identity.ownerSub())));

        MockHttpSession replaySession = copySession(authenticatedSession);
        mvc.perform(get(CALLBACK).session(replaySession)
                        .param("code", "local-auth-code").param("state", state))
                .andExpect(status().is3xxRedirection());
        assertEquals(1, TOKEN_CALLS.get());

        mvc.perform(post("/route/auto-navigate/stop/not-found")
                        .session(authenticatedSession))
                .andExpect(status().isForbidden());
        mvc.perform(post("/route/auto-navigate/stop/not-found")
                        .session(authenticatedSession).with(csrf()))
                .andExpect(status().isNotFound());

        reset(navigation);
        PROFILE.set(profile(SUBJECT, EMAIL, "telemetry"));
        mvc.perform(get("/route/auto-navigate/active")
                        .servletPath("/route/auto-navigate/active").session(authenticatedSession))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("TAP access expired or was revoked. Sign in again."));
        verify(navigation, never()).getActiveSessionForUser(any(TapAccessService.GrantIdentity.class));
        assertNull(clients.loadAuthorizedClient("tap", SUBJECT));
    }

    @Test
    void deniedEntitlementDoesNotCreateAnAuthenticatedSession() throws Exception {
        PROFILE.set(profile(SUBJECT, EMAIL, "telemetry"));
        MvcResult start = mvc.perform(get("/oauth2/authorization/tap"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String state = query(start.getResponse().getRedirectedUrl()).get("state");
        MockHttpSession session = (MockHttpSession) start.getRequest().getSession(false);

        MvcResult denied = mvc.perform(get(CALLBACK).session(session)
                        .param("code", "local-auth-code").param("state", state))
                .andExpect(status().is3xxRedirection()).andReturn();
        mvc.perform(get("/route/auto-navigate/active").session(session))
                .andExpect(status().is3xxRedirection());
        assertEquals("/login?error=true", denied.getResponse().getRedirectedUrl());
        verifyNoInteractions(navigation);
    }

    @Test
    void expiredAuthorizedClientCannotEnterProtectedRoute() throws Exception {
        MvcResult start = mvc.perform(get("/oauth2/authorization/tap"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String state = query(start.getResponse().getRedirectedUrl()).get("state");
        MockHttpSession loginSession = (MockHttpSession) start.getRequest().getSession(false);
        MvcResult login = mvc.perform(get(CALLBACK).session(loginSession)
                        .param("code", "local-auth-code").param("state", state))
                .andExpect(status().is3xxRedirection()).andReturn();
        MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);
        SecurityContext context = (SecurityContext) authenticatedSession.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        OAuth2AuthenticationToken authentication = (OAuth2AuthenticationToken) context.getAuthentication();
        OAuth2AccessToken expired = new OAuth2AccessToken(BEARER, DELEGATED_TOKEN,
                Instant.now().minusSeconds(60), Instant.now().minusSeconds(1));
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                registrations.findByRegistrationId("tap"), SUBJECT, expired);
        clients.saveAuthorizedClient(client, authentication);
        int sessionCalls = SESSION_CALLS.get();

        mvc.perform(get("/route/auto-navigate/active")
                        .servletPath("/route/auto-navigate/active").session(authenticatedSession))
                .andExpect(status().isForbidden());
        assertEquals(sessionCalls, SESSION_CALLS.get());
        assertNull(clients.loadAuthorizedClient("tap", SUBJECT));
        verifyNoInteractions(navigation);
    }

    private static HttpServer startTap() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/delegation/token", exchange -> {
                TOKEN_CALLS.incrementAndGet();
                TOKEN_FORM.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                TOKEN_CLIENT_KEY.set(exchange.getRequestHeaders().getFirst("X-TAP-Client-Key"));
                respond(exchange, 200,
                        "{\"access_token\":\"" + DELEGATED_TOKEN
                                + "\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"routelist\"}");
            });
            server.createContext("/api/v1/delegation/session", exchange -> {
                SESSION_CALLS.incrementAndGet();
                if ("DELETE".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                } else {
                    respond(exchange, 200, PROFILE.get());
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String profile(String subject, String email, String entitlement) {
        return "{\"sub\":\"" + subject + "\",\"email\":\"" + email
                + "\",\"name\":\"TAP user\",\"entitlements\":[\"" + entitlement + "\"]}";
    }

    private static Map<String, String> query(String url) {
        return form(URI.create(url).getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }

    private static MockHttpSession copySession(MockHttpSession original) {
        MockHttpSession copy = new MockHttpSession();
        Enumeration<String> names = original.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            Object attribute = original.getAttribute(name);
            if (HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY.equals(name)
                    && attribute instanceof SecurityContext context) {
                SecurityContext separate = org.springframework.security.core.context.SecurityContextHolder
                        .createEmptyContext();
                separate.setAuthentication(context.getAuthentication());
                attribute = separate;
            }
            copy.setAttribute(name, attribute);
        }
        return copy;
    }
}
