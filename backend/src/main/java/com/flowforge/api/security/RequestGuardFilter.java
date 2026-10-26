package com.flowforge.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.config.FlowForgeProperties;
import com.flowforge.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First line of the localhost trust boundary (there is no user authentication in the single-user MVP):
 * <ol>
 *   <li><b>Host allowlist</b>: requests whose {@code Host} is not an allowed name are refused (403). A web page cannot
 *   use DNS rebinding to make the browser talk to this unauthenticated API under a foreign name.</li>
 *   <li><b>Cross-site writes</b>: state-changing requests (not GET/HEAD/OPTIONS) to the management API from a browser
 *   page of another origin are refused (403), judged by {@code Origin} and {@code Sec-Fetch-Site}. CORS alone does not
 *   stop "simple" cross-site POSTs, which would otherwise run with no credentials needed. Webhooks
 *   ({@code /api/v1/hooks/**}) are exempt: they are server-to-server and authenticate themselves.</li>
 *   <li><b>Body size</b>: management API bodies are capped at {@code flowforge.security.max-request-bytes} (413).</li>
 *   <li><b>Response headers</b>: nosniff, no framing, no referrer, same-origin resource policy, a restrictive CSP for
 *   API responses and {@code Cache-Control: no-store} for credential endpoints.</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestGuardFilter extends OncePerRequestFilter {

    private static final String TYPE_BASE = "https://flowforge.local/problems/";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String HOOKS = "/api/v1/hooks/";

    private final Set<String> allowedHosts;
    private final Set<String> allowedOrigins;
    private final int maxRequestBytes;
    private final ObjectMapper mapper;

    public RequestGuardFilter(SecurityProperties security, FlowForgeProperties properties, ObjectMapper mapper) {
        this.allowedHosts = security.allowedHostNames().stream().map(RequestGuardFilter::normalizeHost)
                .collect(Collectors.toUnmodifiableSet());
        this.allowedOrigins = properties.allowedOrigins().stream().map(o -> o.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.maxRequestBytes = security.maxRequestBytes();
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        securityHeaders(path, response);

        if (!allowedHosts.contains(normalizeHost(request.getServerName()))) {
            reject(response, HttpStatus.FORBIDDEN, "host-not-allowed", "Host not allowed",
                    "This server does not answer to that host name.");
            return;
        }
        boolean hook = path.startsWith(HOOKS);
        if (!hook && !SAFE_METHODS.contains(request.getMethod()) && isCrossSite(request)) {
            reject(response, HttpStatus.FORBIDDEN, "cross-origin-request-blocked", "Cross-origin request blocked",
                    "Requests from other web sites are not accepted.");
            return;
        }
        if (!hook && path.startsWith("/api/")) {
            if (request.getContentLengthLong() > maxRequestBytes) {
                reject(response, HttpStatus.PAYLOAD_TOO_LARGE, "payload-too-large", "Payload too large",
                        "The request body exceeds " + maxRequestBytes + " bytes.");
                return;
            }
            request = new LimitedBodyRequest(request, maxRequestBytes);
        }
        chain.doFilter(request, response);
    }

    private boolean isCrossSite(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            String normalized = origin.strip().toLowerCase(Locale.ROOT);
            return !allowedOrigins.contains(normalized) && !normalized.equals(ownOrigin(request));
        }
        return "cross-site".equalsIgnoreCase(request.getHeader("Sec-Fetch-Site"));
    }

    private static String ownOrigin(HttpServletRequest request) {
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
                || ("https".equals(request.getScheme()) && port == 443);
        return (request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port))
                .toLowerCase(Locale.ROOT);
    }

    private static void securityHeaders(String path, HttpServletResponse response) {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        if (path.startsWith("/api/") || path.startsWith("/actuator")) {
            response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        }
        if (path.startsWith("/api/v1/credentials")) {
            response.setHeader("Cache-Control", "no-store");
        }
    }

    private void reject(HttpServletResponse response, HttpStatus status, String code, String title, String detail)
            throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + code));
        problem.setTitle(title);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }

    static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /** Thrown while reading a body that grows past the limit (chunked uploads have no Content-Length). */
    public static class RequestTooLargeException extends IOException {
        public RequestTooLargeException(int limit) {
            super("Request body exceeds " + limit + " bytes");
        }
    }

    private static final class LimitedBodyRequest extends HttpServletRequestWrapper {

        private final int limit;

        LimitedBodyRequest(HttpServletRequest request, int limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream in = super.getInputStream();
            return new ServletInputStream() {
                private long read;

                @Override
                public int read() throws IOException {
                    int b = in.read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] buffer, int off, int len) throws IOException {
                    int n = in.read(buffer, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) throws RequestTooLargeException {
                    read += n;
                    if (read > limit) {
                        throw new RequestTooLargeException(limit);
                    }
                }

                @Override
                public boolean isFinished() {
                    return in.isFinished();
                }

                @Override
                public boolean isReady() {
                    return in.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    in.setReadListener(listener);
                }
            };
        }
    }
}
