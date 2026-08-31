package com.example.be.domain.reports.service;

import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

/** 결정적 보고서와 Agent 보고서 후처리가 공유하는 Markdown 출력 경계다. */
final class ReportMarkdown {

    private ReportMarkdown() {
    }

    static String text(String value) {
        return singleLine(value).replaceAll("([\\\\`*_{}\\[\\]<>()#+!|])", "\\\\$1");
    }

    static String httpUrl(String value) {
        if (!StringUtils.hasText(value)
                || value.chars().anyMatch(character -> Character.isWhitespace(character)
                || character == '<' || character == '>')) {
            return null;
        }
        try {
            URI uri = new URI(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && StringUtils.hasText(uri.getHost())
                    ? value.trim()
                    : null;
        } catch (URISyntaxException ignored) {
            return null;
        }
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }
}
