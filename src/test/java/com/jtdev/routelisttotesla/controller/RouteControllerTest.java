package com.jtdev.routelisttotesla.controller;

import tools.jackson.databind.ObjectMapper;
import com.jtdev.routelisttotesla.config.SecurityConfig;
import com.jtdev.routelisttotesla.model.AutoNavSession;
import com.jtdev.routelisttotesla.model.PlaceCandidate;
import com.jtdev.routelisttotesla.model.PlaceCandidatesResponse;
import com.jtdev.routelisttotesla.service.AddressOcrService;
import com.jtdev.routelisttotesla.service.AutoNavigationService;
import com.jtdev.routelisttotesla.service.GeocodingClient;
import com.jtdev.routelisttotesla.service.ImageCacheService;
import com.jtdev.routelisttotesla.service.TapAccessService;
import com.jtdev.routelisttotesla.service.TapVehicleClient;
import com.jtdev.routelisttotesla.service.UserSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = RouteController.class, properties = {"tap.client-key=local-test-key", "google.api.key=local-test"})
@Import(SecurityConfig.class)
class RouteControllerTest {
    private static final String OWNER = "tap_subject_driver";
    private static final String VIN = "5YJ3E1EA7JF000000";
    private static final String IDEMPOTENCY_KEY = "route_123456789012345678901234567890";
    private static final TapAccessService.GrantIdentity IDENTITY =
            new TapAccessService.GrantIdentity(OWNER, "ab".repeat(32));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean AddressOcrService ocr;
    @MockitoBean GeocodingClient geocoder;
    @MockitoBean ImageCacheService cache;
    @MockitoBean AutoNavigationService navigation;
    @MockitoBean UserSessionService sessions;
    @MockitoBean TapVehicleClient vehicles;
    @MockitoBean TapAccessService tapAccess;

    @BeforeEach
    void useCurrentGrant() {
        when(tapAccess.grantIdentity(any())).thenReturn(IDENTITY);
    }

    @Test
    void placesCombinesCacheMissesUnderOwnerAndPreservesMultipartAndStopOrder() throws Exception {
        PlaceCandidate first = unresolved("first address", "first.png", 1);
        PlaceCandidate cachedBase = resolved("cached address", "old-name.png", 1);
        PlaceCandidate cached = new PlaceCandidate(cachedBase.text(), cachedBase.normalized(),
                cachedBase.sourceImage(), cachedBase.lineIndex(), cachedBase.lat(), cachedBase.lon(),
                cachedBase.pid(), 2, true, List.of("tesseract: cached address", "easyocr: cached address"));
        PlaceCandidate third = unresolved("third address", "third.png", 3);
        PlaceCandidate resolvedFirst = first.withLatLonPid(42.1, -83.1, "first-id");
        PlaceCandidate resolvedThird = third.withLatLonPid(42.3, -83.3, "third-id");

        when(cache.calculateImageHash(any(byte[].class), anyString(), eq("MI"), eq(OWNER)))
                .thenAnswer(call -> call.getArgument(1, String.class) + "-key");
        when(cache.getCachedResults(anyString())).thenAnswer(call ->
                "middle.png-key".equals(call.getArgument(0)) ? List.of(cached) : null);
        when(ocr.extractAddressCandidates(any(byte[].class), eq("first.png"), eq("MI"))).thenReturn(List.of(first));
        when(ocr.extractAddressCandidates(any(byte[].class), eq("third.png"), eq("MI"))).thenReturn(List.of(third));
        when(geocoder.batchGeocode(eq(OWNER), eq(List.of(first, third)))).thenReturn(List.of(resolvedFirst, resolvedThird));

        mvc.perform(multipart("/route/places")
                        .file(image("first.png"))
                        .file(image("middle.png"))
                        .file(image("third.png"))
                        .param("defaultState", "MI")
                        .with(csrf())
                        .with(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[0].text").value("first address"))
                .andExpect(jsonPath("$.candidates[1].text").value("cached address"))
                .andExpect(jsonPath("$.candidates[1].sourceImage").value("middle.png"))
                .andExpect(jsonPath("$.candidates[1].ocrAgreement").value(2))
                .andExpect(jsonPath("$.candidates[1].ocrReviewRequired").value(true))
                .andExpect(jsonPath("$.candidates[1].ocrAlternatives.length()").value(2))
                .andExpect(jsonPath("$.candidates[2].text").value("third address"));

        verify(vehicles).requireAccess(IDENTITY);
        verify(geocoder, times(1)).batchGeocode(OWNER, List.of(first, third));
        verify(cache).cacheImageResults("first.png-key", "first.png", List.of(resolvedFirst));
        verify(cache).cacheImageResults("third.png-key", "third.png", List.of(resolvedThird));
    }

    @Test
    void placeExtractionRequiresCurrentTapEntitlementBeforeOcrOrGeocoding() throws Exception {
        doThrow(new AccessDeniedException("not entitled")).when(vehicles).requireAccess(IDENTITY);

        mvc.perform(multipart("/route/places")
                        .file(image("route.png"))
                        .with(csrf())
                        .with(login()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ocr, geocoder);
    }

    @Test
    void selectedVehicleMustHaveCommandGrantBeforeAnyGeocodingOrSessionCreation() throws Exception {
        when(vehicles.vehicles(IDENTITY)).thenReturn(List.of(new TapVehicleClient.Vehicle(VIN, "test car", true, false)));
        String unresolved = "{\"candidates\":[{\"text\":\"edited address\"}]}";

        mvc.perform(post("/route/send/" + VIN)
                        .with(csrf()).with(login())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json").content(unresolved))
                .andExpect(status().isForbidden());
        mvc.perform(post("/route/auto-navigate/" + VIN)
                        .with(csrf()).with(login())
                        .contentType("application/json").content(unresolved))
                .andExpect(status().isForbidden());

        verify(vehicles, times(2)).vehicles(IDENTITY);
        verify(geocoder, never()).batchGeocode(anyString(), anyList());
        verify(navigation, never()).sendManualRoute(any(), anyString(), anyList(), anyString());
        verify(navigation, never()).createSession(anyString(), any(), anyList());
    }

    @Test
    void editedStopsAreGeocodedInOneOwnerBatchBeforeSending() throws Exception {
        when(vehicles.vehicles(IDENTITY)).thenReturn(List.of(new TapVehicleClient.Vehicle(VIN, "test car", true, true)));
        PlaceCandidate first = unresolved("same address", "first.png", 1);
        PlaceCandidate repeated = unresolved("same address", "second.png", 2);
        PlaceCandidate resolvedFirst = first.withLatLonPid(42.1, -83.1, "same-id");
        PlaceCandidate resolvedRepeated = repeated.withLatLonPid(42.1, -83.1, "same-id");
        TapVehicleClient.CommandResult pending = new TapVehicleClient.CommandResult("PENDING", null, "pending");
        when(geocoder.batchGeocode(eq(OWNER), eq(List.of(first, repeated))))
                .thenReturn(List.of(resolvedFirst, resolvedRepeated));
        when(navigation.sendManualRoute(IDENTITY, VIN, List.of(resolvedFirst, resolvedRepeated), IDEMPOTENCY_KEY))
                .thenReturn(pending);

        mvc.perform(post("/route/send/" + VIN)
                        .with(csrf()).with(login())
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(new PlaceCandidatesResponse(List.of(first, repeated)))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"));

        verify(geocoder).batchGeocode(OWNER, List.of(first, repeated));
        verify(navigation).sendManualRoute(IDENTITY, VIN, List.of(resolvedFirst, resolvedRepeated), IDEMPOTENCY_KEY);
    }

    @Test
    void cacheLookupIsNamespacedByAuthenticatedTapSubject() throws Exception {
        String contentHash = "a".repeat(64);
        when(cache.calculateClientCacheKey(contentHash, "MI", OWNER)).thenReturn("owner-scoped-key");
        when(cache.getCachedResults("owner-scoped-key")).thenReturn(null);

        mvc.perform(post("/route/cache/check")
                        .with(csrf()).with(login())
                        .contentType("application/json")
                        .content("{\"imageHash\":\"" + contentHash + "\",\"defaultState\":\"MI\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cached").value(false));

        verify(cache).calculateClientCacheKey(contentHash, "MI", OWNER);
        verify(cache).getCachedResults("owner-scoped-key");
    }

    @Test
    void unresolvedConsensusStopsBothDispatchPathsBeforeGeocoding() throws Exception {
        when(vehicles.vehicles(IDENTITY)).thenReturn(List.of(
                new TapVehicleClient.Vehicle(VIN, "test car", true, true)));
        PlaceCandidate ambiguous = new PlaceCandidate("123 MAIN ST", "123 MAIN ST", "route.png",
                0, 0, 0, null, 2, true, List.of("tesseract: 123 MAIN ST APT 3B", "easyocr: 123 MAIN ST"));
        String body = mapper.writeValueAsString(new PlaceCandidatesResponse(List.of(ambiguous)));
        for (String path : List.of("/route/send/", "/route/auto-navigate/")) {
            mvc.perform(post(path + VIN).with(csrf()).with(login())
                            .header("Idempotency-Key", IDEMPOTENCY_KEY)
                            .contentType("application/json").content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(geocoder, navigation);
    }

    @Test
    void sessionStatusAndStopArePinnedToCurrentTapGrantAndCsrfRemainsRequired() throws Exception {
        AutoNavSession session = new AutoNavSession("owned-session", VIN, OWNER, IDENTITY.grantId(),
                List.of(resolved("stop", "route.png", 1)));
        when(navigation.getSession("owned-session", IDENTITY)).thenReturn(session);
        when(navigation.stopSession("owned-session", IDENTITY)).thenReturn(session);

        mvc.perform(get("/route/auto-navigate/status/owned-session").with(login()))
                .andExpect(status().isOk());
        mvc.perform(post("/route/auto-navigate/stop/owned-session").with(login()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/route/auto-navigate/stop/owned-session").with(csrf()).with(login()))
                .andExpect(status().isOk());

        verify(navigation, times(2)).getSession("owned-session", IDENTITY);
        verify(navigation).stopSession("owned-session", IDENTITY);
        verify(sessions).clearActiveAutoNavSession(OWNER);
    }

    private static MockMultipartFile image(String filename) {
        return new MockMultipartFile("images", filename, "image/png", new byte[]{1});
    }

    private static PlaceCandidate unresolved(String text, String filename, int line) {
        return new PlaceCandidate(text, text, filename, line, 0, 0, null);
    }

    private static PlaceCandidate resolved(String text, String filename, int line) {
        return new PlaceCandidate(text, text, filename, line, 42.1, -83.1, text.replace(' ', '-'));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor login() {
        return oidcLogin().idToken(token -> token.subject(OWNER).claim("email", "driver@example.test"));
    }
}
