package com.jtdev.routelisttotesla.config;

import com.jtdev.routelisttotesla.controller.AuthController;
import com.jtdev.routelisttotesla.controller.RouteController;
import com.jtdev.routelisttotesla.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@WebMvcTest(controllers = {AuthController.class, RouteController.class}, properties = {
        "tap.client-key=local-test-key", "google.api.key=local-test"
})
@Import(SecurityConfig.class)
class EndpointSecurityAuditTest {
    private static final List<String> PUBLIC_ASSETS = List.of(
            "/css/tap-theme.css",
            "/fonts/ibm-plex-sans.woff2",
            "/fonts/space-grotesk.woff2",
            "/fonts/ibm-plex-mono.woff2");
    private static final String OWNER_SUB = "audit_tap_subject";
    private static final TapAccessService.GrantIdentity VALID_GRANT =
            new TapAccessService.GrantIdentity(OWNER_SUB, "a".repeat(64));
    private static final OAuth2User TAP_PRINCIPAL = new DefaultOAuth2User(
            List.of(new SimpleGrantedAuthority("ROLE_USER")),
            Map.of("sub", OWNER_SUB, "email", "driver@example.test", "name", "TAP user"),
            "sub");

    @Autowired MockMvc mvc;
    @Autowired RequestMappingHandlerMapping mappings;
    @MockitoBean TapAccessService access;
    @MockitoBean AutoNavigationService navigation;
    @MockitoBean UserSessionService sessions;
    @MockitoBean AddressOcrService ocr;
    @MockitoBean GeocodingClient geocoder;
    @MockitoBean ImageCacheService cache;
    @MockitoBean TapVehicleClient vehicles;

    @BeforeEach
    void mapPrincipalToGrant() {
        when(access.grantIdentity(any(OAuth2User.class))).thenReturn(VALID_GRANT);
    }

    @Test
    void exactGetAssetsArePublicAndSkipTapEntitlementChecks() throws Exception {
        doThrow(new AccessDeniedException("revoked")).when(access).requireAccess(VALID_GRANT);

        for (String path : PUBLIC_ASSETS) {
            var response = mvc.perform(request(HttpMethod.GET, path).servletPath(path)
                    .with(oauth2Login().oauth2User(TAP_PRINCIPAL))).andReturn().getResponse();
            assertEquals(200, response.getStatus(), path);
        }

        verifyNoInteractions(access);
    }

    @Test
    void unknownAssetsAndNonGetAssetsRemainProtected() throws Exception {
        String unknownPath = "/fonts/not-approved.woff2";
        var anonymous = mvc.perform(request(HttpMethod.GET, unknownPath).servletPath(unknownPath))
                .andReturn().getResponse();
        assertEquals(302, anonymous.getStatus());
        assertEquals("/login", anonymous.getRedirectedUrl());

        doThrow(new AccessDeniedException("revoked")).when(access).requireAccess(VALID_GRANT);
        var unknown = mvc.perform(request(HttpMethod.GET, unknownPath).servletPath(unknownPath)
                .with(oauth2Login().oauth2User(TAP_PRINCIPAL))).andReturn().getResponse();
        assertEquals(302, unknown.getStatus());
        assertEquals("/login?error=true", unknown.getRedirectedUrl());

        var post = mvc.perform(request(HttpMethod.POST, "/css/tap-theme.css")
                .servletPath("/css/tap-theme.css").with(csrf())
                .with(oauth2Login().oauth2User(TAP_PRINCIPAL))).andReturn().getResponse();
        assertEquals(302, post.getStatus());
        assertEquals("/login?error=true", post.getRedirectedUrl());
        verify(access, times(2)).requireAccess(VALID_GRANT);
    }

    @Test
    void everyRouteEndpointRejectsAnonymousRequestsEvenWithValidCsrf() throws Exception {
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (entry.getValue().getBeanType() != RouteController.class) continue;
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "audit-nonexistent");
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    var response = mvc.perform(request(HttpMethod.valueOf(method.name()), path).servletPath(path)
                            .with(csrf())).andReturn().getResponse();
                    assertEquals(302, response.getStatus(), method + " " + pattern);
                assertEquals("/login", response.getRedirectedUrl(), pattern);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 13, "All registered route endpoints must be included");
        verifyNoInteractions(access, navigation, sessions, ocr, geocoder, cache, vehicles);
    }

    @Test
    void everyRouteMutationRequiresCsrfBeforeCallingServices() throws Exception {
        int checked = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (entry.getValue().getBeanType() != RouteController.class) continue;
            for (String pattern : entry.getKey().getPatternValues()) {
                String path = pattern.replaceAll("\\{[^}]+}", "audit-nonexistent");
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    if (method.name().equals("GET")) continue;
                    var response = mvc.perform(request(HttpMethod.valueOf(method.name()), path).servletPath(path)
                            .with(oauth2Login().oauth2User(TAP_PRINCIPAL))).andReturn().getResponse();
                    assertEquals(403, response.getStatus(), method + " " + pattern);
                    checked++;
                }
            }
        }
        assertTrue(checked >= 7, "All route mutations must be covered");
        verifyNoInteractions(navigation, sessions, ocr, geocoder, cache, vehicles);
    }

    @Test
    void rejectedOrUnavailableTapEntitlementNeverReachesApplicationServices() throws Exception {
        for (RuntimeException failure : new RuntimeException[]{
                new AccessDeniedException("revoked"), new IllegalStateException("offline")}) {
        doThrow(failure).when(access).requireAccess(VALID_GRANT);
            var response = mvc.perform(request(HttpMethod.GET, "/route/vehicles").servletPath("/route/vehicles")
                    .with(oauth2Login().oauth2User(TAP_PRINCIPAL))).andReturn().getResponse();
            assertEquals(failure instanceof AccessDeniedException ? 403 : 503, response.getStatus());
        }
        verify(access, times(2)).grantIdentity(any(OAuth2User.class));
        verify(access, times(2)).requireAccess(VALID_GRANT);
        verifyNoInteractions(navigation, sessions, ocr, geocoder, cache, vehicles);
    }
}
