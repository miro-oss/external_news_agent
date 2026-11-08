package com.example.be.domain.reports.controller;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Includes stale selections, malformed requests and local access denials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ReportWatchItemNoStoreFilter extends OncePerRequestFilter {
    /** Sets cache policy before access checks so errors and rejected requests cannot be cached either. */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.matches("/api/news/reports/[^/]+/watch-items/promote")) response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
