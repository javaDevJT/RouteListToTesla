package com.jtdev.routelisttotesla.config;

import com.jtdev.routelisttotesla.controller.AuthController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@WebMvcTest(controllers = AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class WebBoundaryRenderingTest {
    @Autowired WebApplicationContext context;

    @Test void homeRendersTheServerNonceAndNoInlineHandlers() throws Exception {
        // Authentication boundaries are covered by EndpointSecurityAuditTest; this checks actual Thymeleaf output.
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new WebBoundaryFilter()).build();
        var response = mvc.perform(get("/")
                .requestAttr("_csrf", new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-csrf")))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        String html = response.getContentAsString();
        var nonce = Pattern.compile("script-src 'nonce-([^']+)'")
                .matcher(response.getHeader("Content-Security-Policy"));
        assertTrue(nonce.find());
        assertTrue(html.contains("<script nonce=\"" + nonce.group(1) + "\">"));
        assertFalse(Pattern.compile("\\son[a-z]+=\"").matcher(html).find());
        assertTrue(html.contains("data-action=\"startAutoNavigation\""));
        assertTrue(html.contains("OCR agreement:"));
        assertTrue(html.contains("ocrReviewRequired"));
        assertTrue(html.contains("ocrAlternatives"));
        assertTrue(html.contains("confirmOcrReading"));
        assertTrue(html.contains("item.textContent = typeof reading === 'string' ? reading : ''"));
        assertTrue(html.contains("groupAddresses.some(address => address.ocrReviewRequired === true)"));
        assertTrue(html.contains("extractedAddresses.some(address => address.ocrReviewRequired === true)"));
    }
}
