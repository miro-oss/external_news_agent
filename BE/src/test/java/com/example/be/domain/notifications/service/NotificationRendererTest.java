package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationRendererTest {

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final NotificationRenderer renderer = new NotificationRenderer(findingRepository);

    @Test
    void emailContainsOnlySummaryAndSourceLink() {
        NewsReport report = report("# 내부 보고서\n기사 전문을 그대로 싣지 말 것");
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding()));

        RenderedNotification rendered = renderer.render(report, channel(ChannelType.EMAIL, Integer.MAX_VALUE));

        String body = rendered.chunks().getFirst();
        assertTrue(body.contains("검증된 핵심 요약"));
        assertTrue(body.contains("https://example.com/article"));
        assertFalse(body.contains("기사 전문을 그대로 싣지 말 것"));
        assertFalse(body.contains("원문 전체 내용"));
    }

    @Test
    void telegramSplitsAtConfiguredSafeLength() {
        NewsReport report = report("# 내부 보고서");
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding(), finding()));

        RenderedNotification rendered = renderer.render(report, channel(ChannelType.TELEGRAM, 120));

        assertTrue(rendered.chunks().size() >= 2);
        assertTrue(rendered.chunks().stream().allMatch(chunk -> chunk.length() <= 120));
        assertTrue(rendered.chunks().stream().noneMatch(chunk -> chunk.contains("원문 전체 내용")));
    }

    @Test
    void telegramKeepsEscapedTextWithinVerySmallConfiguredLength() {
        NewsReport report = NewsReport.builder()
                .id(17L)
                .run(CollectionRun.builder().id(42L).build())
                .title("<&".repeat(30))
                .markdownBody("# 내부 보고서")
                .generatedAt(LocalDateTime.of(2026, 8, 27, 10, 0))
                .build();
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of());

        RenderedNotification rendered = renderer.render(report, channel(ChannelType.TELEGRAM, 12));

        assertTrue(rendered.chunks().stream().allMatch(chunk -> chunk.length() <= 12));
        assertTrue(rendered.chunks().stream().noneMatch(chunk -> chunk.endsWith("&")));
    }

    @Test
    void breakingAlertEscapesChannelSpecificMarkup() {
        RenderedNotification email = renderer.renderBreakingAlert(
                "HBM4", "속보 '<HBM4>'에 후속 1건", channel(ChannelType.EMAIL, Integer.MAX_VALUE));
        RenderedNotification telegram = renderer.renderBreakingAlert(
                "HBM4", "속보 '<HBM4>'에 후속 1건", channel(ChannelType.TELEGRAM, 3500));

        assertTrue(email.subject().startsWith("[속보 후속]"));
        assertTrue(email.chunks().getFirst().contains("&#39;&lt;HBM4&gt;&#39;"));
        assertTrue(telegram.chunks().getFirst().contains("&#39;&lt;HBM4&gt;&#39;"));
    }

    private NewsReport report(String markdown) {
        return NewsReport.builder()
                .id(17L)
                .run(CollectionRun.builder().id(42L).build())
                .title("반도체 뉴스 보고서")
                .markdownBody(markdown)
                .generatedAt(LocalDateTime.of(2026, 8, 27, 10, 0))
                .build();
    }

    private NotificationChannel channel(ChannelType type, int maxLength) {
        return NotificationChannel.builder()
                .id(type == ChannelType.TELEGRAM ? 1L : 2L)
                .channelType(type)
                .name(type.name())
                .config(type == ChannelType.TELEGRAM ? Map.of("parseMode", "HTML") : Map.of())
                .maxLength(maxLength)
                .active(true)
                .build();
    }

    private Finding finding() {
        return Finding.builder()
                .id(1L)
                .article(Article.builder()
                        .id(101L)
                        .title("HBM 공급 계약 확대")
                        .canonicalUrl("https://example.com/article")
                        .body("원문 전체 내용")
                        .build())
                .summary("검증된 핵심 요약")
                .riskLevel(RiskLevel.HIGH)
                .relevance(Relevance.IMPORTANT)
                .build();
    }
}
