package com.jtdev.routelisttotesla.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WebBoundaryFilterTest {
    private final WebBoundaryFilter filter = new WebBoundaryFilter();

    @Test void boundsDeclaredAndChunkedJsonBeforeDownstream() throws Exception {
        for (boolean chunked : new boolean[]{false, true}) {
            MockHttpServletRequest request = new MockHttpServletRequest() {
                @Override public long getContentLengthLong() {
                    return chunked ? -1 : super.getContentLengthLong();
                }
            };
            request.setContentType("application/json;charset=UTF-8");
            request.setContent(new byte[WebBoundaryFilter.MAX_JSON_BYTES + 1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, response, (req, res) -> reached.set(true));
            assertEquals(413, response.getStatus());
            assertFalse(reached.get());
        }
    }

    @Test void preservesJsonAndGeneratesDistinctNoncesAndHeaders() throws Exception {
        String previous = null;
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setContentType("application/json");
            request.setContent("{\"addresses\":[\"7 Main St\"]}".getBytes(StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) ->
                    assertEquals("{\"addresses\":[\"7 Main St\"]}",
                            new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            String nonce = (String) request.getAttribute("cspNonce");
            assertNotNull(nonce);
            assertNotEquals(previous, nonce);
            assertTrue(response.getHeader("Content-Security-Policy").contains("script-src 'nonce-" + nonce + "'"));
            assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
            assertEquals("camera=(), microphone=(), geolocation=()", response.getHeader("Permissions-Policy"));
            previous = nonce;
        }
    }
}
