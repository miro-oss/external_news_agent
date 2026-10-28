package com.example.be.domain.reports.coverage;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Provider labels vary (publisher name vs URL host); count the same original publishing site once. */
final class ReportPublisherIdentity {
    private static final Set<String> PLATFORMS = Set.of("google.com", "google.co.kr", "naver.com", "bing.com",
            "news.yahoo.com", "news.google.com", "news.daum.net", "v.daum.net", "search.daum.net");

    private ReportPublisherIdentity() { }

    static String key(String publisher, String canonicalUrl) {
        if (ReportCoverageCapture.publisher(publisher) == null) return null;
        String safeUrl = ReportCoverageService.safeUrl(canonicalUrl);
        if (safeUrl == null) return null;
        String host = URI.create(safeUrl).getHost().toLowerCase(Locale.ROOT).replaceFirst("\\.$", "");
        if (host.startsWith("www.")) host = host.substring(4);
        final String normalized = host;
        if (PLATFORMS.stream().anyMatch(platform -> normalized.equals(platform) || normalized.endsWith("." + platform))) return null;
        // Do not guess a corporate family, merge arbitrary subdomains or infer publisher aliases.
        return "site:" + host;
    }
}
