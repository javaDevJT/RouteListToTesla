package com.jtdev.routelisttotesla.controller;

import com.jtdev.routelisttotesla.config.SecurityConfig;
import com.jtdev.routelisttotesla.service.AutoNavigationService;
import com.jtdev.routelisttotesla.service.TapAccessService;
import com.jtdev.routelisttotesla.service.UserSessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = AuthController.class, properties = {
        "tap.client-key=local-test-key",
        "google.api.key=local-test"
})
@Import(SecurityConfig.class)
class AuthPageTemplateTest {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    TapAccessService tapAccess;

    @MockitoBean
    UserSessionService sessions;

    @MockitoBean
    AutoNavigationService navigation;

    @Test
    void authenticatedIndexRendersCompleteVehicleInitializationScript() throws Exception {
        String subject = "tap_subject_browser_test";
        TapAccessService.GrantIdentity grantIdentity =
                new TapAccessService.GrantIdentity(subject, "a".repeat(64));
        when(tapAccess.grantIdentity(any(OAuth2User.class))).thenReturn(grantIdentity);
        doNothing().when(tapAccess).requireAccess(grantIdentity);

        MvcResult result = mvc.perform(get("/")
                        .with(oidcLogin().idToken(token -> token.subject(subject)
                                .claim("email", "driver@example.test")
                                .claim("name", "Test Driver"))))
                .andExpect(status().isOk())
                .andReturn();
        verify(tapAccess).grantIdentity(any(OAuth2User.class));
        verify(tapAccess).requireAccess(grantIdentity);

        String html = result.getResponse().getContentAsString();
        assertTrue(html.contains("</html>"), "The index response should not be truncated");

        Matcher scripts = Pattern.compile("(?is)<script\\b[^>]*>(.*?)</script\\s*>").matcher(html);
        assertTrue(scripts.find(), "The index response should contain its client script");
        String script = scripts.group(1);
        assertTrue(script.contains("async function loadVehicles(savedVin)"),
                "Vehicle loading logic should render completely");
        assertTrue(script.contains("window.addEventListener('DOMContentLoaded'"),
                "The page initialization handler should render");
        assertTrue(script.contains("await loadVehicles(savedVin);"),
                "The page should invoke vehicle loading during initialization");
        assertTrue(script.contains("document.getElementById('vin').addEventListener('change'"),
                "The VIN change handler should render after initialization");
    }
}
