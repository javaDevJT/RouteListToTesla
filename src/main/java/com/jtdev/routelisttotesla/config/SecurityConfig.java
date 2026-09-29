package com.jtdev.routelisttotesla.config;

import com.jtdev.routelisttotesla.service.TapAccessService;
import com.jtdev.routelisttotesla.service.UserSessionService;
import com.jtdev.routelisttotesla.service.AutoNavigationService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Configuration
public class SecurityConfig {
    private static final Set<String> PUBLIC_GET_PATHS = Set.of(
            "/login",
            "/oauth2/authorization/tap",
            "/login/oauth2/code/tap",
            "/css/tap-theme.css",
            "/fonts/ibm-plex-sans.woff2",
            "/fonts/space-grotesk.woff2",
            "/fonts/ibm-plex-mono.woff2");

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ClientRegistrationRepository registrations,
                                           TapAccessService access, UserSessionService savedRoutes, AutoNavigationService navigation,
                                           @Value("${tap.client-key:}") String clientKey,
                                           @Value("${TAP_LEGACY_OWNER_EMAIL:}") String legacyEmail,
                                           @Value("${TAP_LEGACY_OWNER_SUB:}") String legacySubject) throws Exception {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        var loginEntryPoint = new LoginUrlAuthenticationEntryPoint("/login");
        loginEntryPoint.setFavorRelativeUris(true);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(15_000);
        tokenClient.setRestClient(RestClient.builder().requestFactory(factory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                }).defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        tokenClient.addHeadersConverter(request -> {
            if (clientKey.isBlank()) throw new OAuth2AuthenticationException(new OAuth2Error("invalid_client"), "TAP client authentication is not configured");
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-TAP-Client-Key", clientKey);
            headers.set("User-Agent", "RouteListToTesla/0.0.4");
            return headers;
        });

        http.authorizeHttpRequests(authz -> authz
                .requestMatchers(HttpMethod.GET, PUBLIC_GET_PATHS.toArray(String[]::new)).permitAll()
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                    .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(loginEntryPoint))
                .oauth2Login(oauth2 -> oauth2.loginPage("/login").successHandler((request, response, authentication) -> {
                    if (!legacyEmail.isBlank() && authentication.getName().equals(legacySubject)) {
                        savedRoutes.migrateOwner(legacyEmail, legacySubject);
                        navigation.migrateOwner(legacyEmail, legacySubject);
                    }
                    response.sendRedirect("/");
                })
                    .failureUrl("/login?error=true")
                    .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(resolver))
                    .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(tokenClient))
                    .userInfoEndpoint(endpoint -> endpoint.userService(request -> {
                        try {
                            return access.identify(request.getAccessToken());
                        } catch (AccessDeniedException | IllegalStateException e) {
                            throw new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "TAP access was not granted");
                        }
                    })))
                .sessionManagement(session -> session.sessionFixation().changeSessionId())
                .logout(logout -> logout.addLogoutHandler((request, response, authentication) -> {
                    if (authentication != null && authentication.getPrincipal() instanceof OAuth2User principal) {
                        access.revoke(access.grantIdentity(principal));
                    }
                }).logoutSuccessUrl("/login?logout=true").permitAll())
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected boolean shouldNotFilter(HttpServletRequest request) {
                        String path = request.getServletPath();
                        if (request.getDispatcherType() == DispatcherType.ERROR) return true;
                    return "GET".equals(request.getMethod()) && PUBLIC_GET_PATHS.contains(path);
                    }

                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                                    FilterChain chain) throws ServletException, IOException {
                        var authentication = SecurityContextHolder.getContext().getAuthentication();
                        if (authentication != null && authentication.isAuthenticated()
                                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                            TapAccessService.GrantIdentity identity = null;
                            try {
                                if (!(authentication.getPrincipal() instanceof OAuth2User principal)) {
                                    throw new AccessDeniedException("TAP principal is unavailable");
                                }
                                identity = access.grantIdentity(principal);
                                access.requireAccess(identity);
                            } catch (AccessDeniedException denied) {
                                access.forget(identity);
                                SecurityContextHolder.clearContext();
                                if (request.getSession(false) != null) request.getSession(false).invalidate();
                                if (request.getServletPath().startsWith("/route/")) {
                                    response.setStatus(403);
                                    response.setContentType("application/json");
                                    response.getWriter().write("{\"error\":\"TAP access expired or was revoked. Sign in again.\"}");
                                } else response.sendRedirect("/login?error=true");
                                return;
                            } catch (IllegalStateException unavailable) {
                                response.setStatus(503);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"error\":\"TAP access verification is unavailable. Try again.\"}");
                                return;
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, AuthorizationFilter.class);
        return http.build();
    }
}
