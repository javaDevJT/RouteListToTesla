package com.jtdev.routelisttotesla.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class WebBoundaryFilter extends OncePerRequestFilter {
    static final int MAX_JSON_BYTES = 256 * 1024;
    private final SecureRandom random = new SecureRandom();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        request.setAttribute("cspNonce", nonce);
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'nonce-" + nonce
                + "'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'; "
                + "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

        String type = request.getContentType();
        String mediaType = type == null ? "" : type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
            if (request.getContentLengthLong() > MAX_JSON_BYTES) {
                reject(response);
                return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_JSON_BYTES + 1);
            if (body.length > MAX_JSON_BYTES) {
                reject(response);
                return;
            }
            request = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    ByteArrayInputStream input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        @Override public int read() { return input.read(); }
                        @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                        @Override public boolean isFinished() { return input.available() == 0; }
                        @Override public boolean isReady() { return true; }
                        @Override public void setReadListener(ReadListener listener) {
                            throw new UnsupportedOperationException("Synchronous JSON request body");
                        }
                    };
                }
            };
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Request body exceeds 256 KiB\"}");
    }
}
