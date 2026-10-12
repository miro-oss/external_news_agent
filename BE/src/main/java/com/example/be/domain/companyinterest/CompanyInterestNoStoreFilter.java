package com.example.be.domain.companyinterest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Includes validation failures and local access denials, which never reach the controller. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CompanyInterestNoStoreFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getRequestURI().equals("/api/news/company-interests")
                || request.getRequestURI().startsWith("/api/news/company-interests/"))
            response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
