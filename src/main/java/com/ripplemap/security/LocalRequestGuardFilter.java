package com.ripplemap.security;

import com.ripplemap.config.RippleMapProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Request guard for a local single-user tool:
 * <ul>
 *   <li>rejects requests whose Host header is not an allowed local name (DNS-rebinding protection);</li>
 *   <li>for state-changing methods, rejects cross-site requests (Origin / Sec-Fetch-Site) and requires the
 *       {@link CsrfTokenService#HEADER} token;</li>
 *   <li>adds security headers (CSP, nosniff, frame denial, no referrer) to every response.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuardFilter extends OncePerRequestFilter {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; "
            + "form-action 'self'";
    static final String SWAGGER_CSP = "default-src 'self'; script-src 'self' 'unsafe-inline'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; "
            + "font-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";
    /** Downloaded reports are standalone documents: inline styles only, nothing may be loaded. */
    static final String REPORT_CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; frame-ancestors 'none'";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedHosts;
    private final CsrfTokenService csrf;

    public LocalRequestGuardFilter(RippleMapProperties properties, CsrfTokenService csrf) {
        this.allowedHosts = properties.getSecurity().getAllowedHosts().stream()
                .map(h -> h.toLowerCase(Locale.ROOT).strip())
                .collect(Collectors.toUnmodifiableSet());
        this.csrf = csrf;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        addSecurityHeaders(request, response);
        String hostHeader = request.getHeader("Host");
        // a DNS-rebinding attack always carries the attacker's Host header; requests without one (HTTP/1.0)
        // are judged by the server name the connector reports
        String host = hostHeader != null ? hostName(hostHeader) : normalizeHost(request.getServerName());
        if (host == null || !allowedHosts.contains(host)) {
            reject(response, request, HttpServletResponse.SC_FORBIDDEN, "HOST_NOT_ALLOWED",
                    "Requests must use a local host name such as localhost or 127.0.0.1");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if ("cross-site".equalsIgnoreCase(fetchSite)) {
                reject(response, request, HttpServletResponse.SC_FORBIDDEN, "CROSS_SITE_REQUEST",
                        "Cross-site requests are not allowed");
                return;
            }
            String origin = request.getHeader("Origin");
            if (origin != null && !originAllowed(origin, request)) {
                reject(response, request, HttpServletResponse.SC_FORBIDDEN, "ORIGIN_NOT_ALLOWED",
                        "The request origin is not allowed");
                return;
            }
            if (!csrf.matches(request.getHeader(CsrfTokenService.HEADER))) {
                reject(response, request, HttpServletResponse.SC_FORBIDDEN, "CSRF_TOKEN_INVALID",
                        "The security token is missing or expired. Reload the page and try again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean originAllowed(String origin, HttpServletRequest request) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (host == null || !allowedHosts.contains(normalizeHost(host))) {
                return false;
            }
            int port = uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
            return port == request.getServerPort() || port == request.getLocalPort();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void addSecurityHeaders(HttpServletRequest request, HttpServletResponse response) {
        String path = request.getRequestURI();
        boolean swagger = path.startsWith("/swagger-ui") || path.startsWith("/api-docs");
        boolean report = path.startsWith("/api/projects/") && path.contains("/reports/");
        response.setHeader("Content-Security-Policy", swagger ? SWAGGER_CSP : report ? REPORT_CSP : CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (path.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
        }
    }

    /** Extracts the lower-cased host name from a Host header value ("localhost:8080", "[::1]:8080"). */
    static String hostName(String hostHeader) {
        if (hostHeader == null || hostHeader.isBlank()) {
            return null;
        }
        String value = hostHeader.strip().toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? null : value.substring(0, end + 1);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return h.contains(":") && !h.startsWith("[") ? "[" + h + "]" : h;
    }

    private static void reject(HttpServletResponse response, HttpServletRequest request, int status, String code,
                               String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        String body = "{\"status\":" + status + ",\"error\":\"Forbidden\",\"code\":\"" + code + "\",\"message\":\""
                + message + "\",\"path\":\"" + jsonEscape(request.getRequestURI()) + "\",\"timestamp\":\""
                + Instant.now() + "\",\"fieldErrors\":[]}";
        response.getWriter().write(body);
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20 || c == '<' || c == '>' || c == '&') {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
