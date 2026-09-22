package com.example.be.global.config;

import com.example.be.global.apiPayload.ApiResponse;
import com.example.be.global.apiPayload.code.GeneralErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;

/** Keeps the unauthenticated product API inside its local PoC trust boundary. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalRequestGuard extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public LocalRequestGuard(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        if (!isAllowed(request)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(),
                    ApiResponse.onFailure(GeneralErrorCode.BAD_REQUEST, Map.of()));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAllowed(HttpServletRequest request) {
        // Never trust Forwarded/X-Forwarded-* to grant access to this local-only service.
        if (!isLoopbackLiteral(request.getRemoteAddr()) || !isLocalHost(request.getServerName())) {
            return false;
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !isLocalUrl(origin, true)) {
            return false;
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !isLocalUrl(referer, false)) {
            return false;
        }
        // Vite can forward a local Origin with a different port/loopback spelling.
        // Other cross-site requests, including forms and image loads, must not reach controllers.
        String fetchSite = request.getHeader("Sec-Fetch-Site");
        return !"cross-site".equalsIgnoreCase(fetchSite) || origin != null;
    }

    private static boolean isLocalUrl(String value, boolean origin) {
        try {
            URI uri = URI.create(value);
            boolean http = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            return http && isLocalHost(uri.getHost()) && uri.getRawUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && (!origin || (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isLocalHost(String value) {
        return value != null && ("localhost".equals(value.toLowerCase(Locale.ROOT))
                || isLoopbackLiteral(value));
    }

    private static boolean isLoopbackLiteral(String value) {
        return "127.0.0.1".equals(value) || "::1".equals(value) || "[::1]".equals(value)
                || "0:0:0:0:0:0:0:1".equals(value) || "[0:0:0:0:0:0:0:1]".equals(value);
    }
}
