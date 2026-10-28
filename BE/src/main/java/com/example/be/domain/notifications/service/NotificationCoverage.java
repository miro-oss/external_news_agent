package com.example.be.domain.notifications.service;

import com.example.be.domain.reports.coverage.ReportCoverageDTO;
import com.example.be.global.config.ApiTimeZone;

/** Shared words and states for the compact email and offline PDF coverage metadata. */
final class NotificationCoverage {
    private NotificationCoverage() { }

    static String label(ReportCoverageDTO.EventCoverage coverage) {
        if (coverage == null || coverage.status() != ReportCoverageDTO.Status.AVAILABLE) return "집계 자료 없음";
        String label = "관련 기사 " + coverage.articleCount() + "건 · " + coverage.publisherCount() + "개 매체";
        return coverage.unknownPublisherArticleCount() != null && coverage.unknownPublisherArticleCount() > 0
                ? label + " · 매체 미확인 " + coverage.unknownPublisherArticleCount() + "건" : label;
    }

    static String metadata(ReportCoverageDTO.Article article) {
        return (article.publisher() == null || article.publisher().isBlank() ? "매체 미확인" : article.publisher())
                + " · " + (article.publishedAt() == null ? "발행일 미확인"
                : article.publishedAt().atZoneSameInstant(ApiTimeZone.ZONE).toLocalDate());
    }
}
