package com.jtdev.routelisttotesla.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The application key authenticates login exchanges; only user grants authorize application access. */
@Service
public class TapAccessService {
    private static final String REGISTRATION_ID = "tap";

    private final ObjectMapper mapper;
    private final OAuth2AuthorizedClientService clients;
    private final URI sessionUri;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public TapAccessService(ObjectMapper mapper,
                            OAuth2AuthorizedClientService clients,
                            @Value("${tap.base-url}") String baseUrl) {
        this.mapper = mapper;
        this.clients = clients;
        this.sessionUri = baseUri(baseUrl).resolve("delegation/session");
    }

    public static URI baseUri(String baseUrl) {
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/");
        boolean secure = "https".equals(uri.getScheme());
        boolean loopbackHttp = "http".equals(uri.getScheme())
                && List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || (!secure && !loopbackHttp)) {
            throw new IllegalArgumentException("TAP base URL must use HTTPS (HTTP allowed for loopback tests)");
        }
        return uri;
    }

    /** Exchange a grant for its current TAP identity and bind the browser principal to that grant. */
    public OAuth2User identify(OAuth2AccessToken token) {
        checkExpiry(token);
        JsonNode profile = session(token.getTokenValue(), false);
        String subject = profile.path("sub").asString("");
        if (!subject.matches("[A-Za-z0-9_-]{1,128}") || !profile.path("entitlements").isArray()
                || !containsEntitlement(profile.path("entitlements"))) {
            throw new AccessDeniedException("TAP has not granted TeslaRouter access to this account");
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("sub", subject);
        attributes.put("email", profile.path("email").asString(""));
        attributes.put("name", profile.path("name").asString("TAP user"));
        GrantIdentity identity = new GrantIdentity(subject, fingerprint(token.getTokenValue()));
        return new TapPrincipal(attributes, identity);
    }

    /** Return the grant identity carried privately by the authenticated TAP principal. */
    public GrantIdentity grantIdentity(OAuth2User principal) {
        if (principal instanceof TapPrincipal tapPrincipal) {
            return tapPrincipal.grantIdentity();
        }
        throw new AccessDeniedException("Sign in through TAP again");
    }

    private boolean containsEntitlement(JsonNode entitlements) {
        for (JsonNode entitlement : entitlements) {
        if ("routelist".equals(entitlement.asString())) return true;
        }
        return false;
    }

    /** Validate the browser's grant against the single authorized-client slot for its TAP subject. */
    public void requireAccess(GrantIdentity identity) {
        OAuth2AuthorizedClient client = currentClient(identity);
        try {
            OAuth2User currentIdentity = identify(client.getAccessToken());
            if (!identity.ownerSub().equals(currentIdentity.getName())) {
                forget(identity);
                throw new AccessDeniedException("TAP returned a different account");
            }
        } catch (AccessDeniedException e) {
            forget(identity);
            throw e;
        }
        // The authorized-client slot may have been replaced while TAP was checked.
        currentClient(identity);
    }

    /**
     * Authorize and dispatch through the same single-slot lock used by OAuth login saves.
     * The bounded TAP request intentionally serializes authorized-client replacement/removal
     * across users while it is in flight; TapVehicleClient applies a 35-second HTTP timeout.
     */
 public <T> T withToken(GrantIdentity identity, Function<String, T> operation) {
 // ponytail: This global lock caps throughput at one request for any owner; use per-subject
 // locks if contention warrants it, preserving atomic grant save, removal, and dispatch.
        if (operation == null) throw new IllegalArgumentException("A TAP operation is required");
        requireAccess(identity);
        synchronized (clients) {
            return operation.apply(currentClient(identity).getAccessToken().getTokenValue());
        }
    }

    /** Revoke and remove only this exact grant; an old browser cannot remove its replacement. */
    public void revoke(GrantIdentity identity) {
        OAuth2AuthorizedClient client = removeIfCurrent(identity);
        if (client == null) return;
        try {
            session(client.getAccessToken().getTokenValue(), true);
        } catch (AccessDeniedException | IllegalStateException ignored) {
            // Local logout always removes background access, even if TAP is unavailable.
        }
    }

    /** Remove this exact grant locally without contacting TAP. */
    public void forget(GrantIdentity identity) {
        removeIfCurrent(identity);
    }

    private OAuth2AuthorizedClient currentClient(GrantIdentity identity) {
        if (identity == null) throw new AccessDeniedException("Sign in through TAP first");
        synchronized (clients) {
            OAuth2AuthorizedClient client = clients.loadAuthorizedClient(REGISTRATION_ID, identity.ownerSub());
            if (client == null) throw new AccessDeniedException("Sign in through TAP again to restore vehicle access");
            checkExpiry(client.getAccessToken());
            if (!identity.grantId().equals(fingerprint(client.getAccessToken().getTokenValue()))) {
                throw new AccessDeniedException("TAP grant changed; sign in again");
            }
            return client;
        }
    }

    private OAuth2AuthorizedClient removeIfCurrent(GrantIdentity identity) {
        if (identity == null) return null;
        synchronized (clients) {
            OAuth2AuthorizedClient client = clients.loadAuthorizedClient(REGISTRATION_ID, identity.ownerSub());
            if (client == null || !identity.grantId().equals(fingerprint(client.getAccessToken().getTokenValue()))) {
                return null;
            }
            clients.removeAuthorizedClient(REGISTRATION_ID, identity.ownerSub());
            return client;
        }
    }

    private static String fingerprint(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void checkExpiry(OAuth2AccessToken token) {
        if (token == null || token.getExpiresAt() == null || !token.getExpiresAt().isAfter(Instant.now())) {
            throw new AccessDeniedException("TAP access expired; sign in again");
        }
    }

    private JsonNode session(String token, boolean revoke) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(sessionUri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json")
                    .header("User-Agent", "RouteListToTesla/0.0.4");
            if (revoke) request.DELETE();
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new AccessDeniedException("TAP access was denied or revoked");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("TAP access verification is unavailable");
            }
            if (revoke) return mapper.createObjectNode();
            JsonNode profile = mapper.readTree(response.body());
            if (profile == null || !profile.isObject()) throw new IllegalStateException("Invalid TAP identity response");
            return profile;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TAP access verification was interrupted");
        } catch (IOException | JacksonException e) {
            throw new IllegalStateException("TAP access verification is unavailable");
        }
    }

    /** Owner identity and credential fingerprint are private authentication state, never profile attributes. */
    public record GrantIdentity(String ownerSub, String grantId) implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        public GrantIdentity {
            if (ownerSub == null || !ownerSub.matches("[A-Za-z0-9_-]{1,128}")
                    || grantId == null || !grantId.matches("[a-f0-9]{64}")) {
                throw new IllegalArgumentException("Invalid TAP grant identity");
            }
        }
    }

    private static final class TapPrincipal implements OAuth2User, Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final Map<String, Object> attributes;
        private final List<GrantedAuthority> authorities;
        private final GrantIdentity grantIdentity;

        private TapPrincipal(Map<String, Object> attributes, GrantIdentity grantIdentity) {
            this.attributes = Map.copyOf(attributes);
            this.authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
            this.grantIdentity = grantIdentity;
        }

        private GrantIdentity grantIdentity() {
            return grantIdentity;
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public List<GrantedAuthority> getAuthorities() {
            return authorities;
        }

        @Override
        public String getName() {
            return (String) attributes.get("sub");
        }
    }
}
