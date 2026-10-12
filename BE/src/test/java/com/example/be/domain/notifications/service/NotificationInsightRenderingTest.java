package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationInsightRenderingTest {
    private final NotificationRenderer renderer = new NotificationRenderer(mock(FindingRepository.class),
            TopicRelevanceTestSupport.legacyPolicy(), mock(ReportEventFeedbackProjection.class));
    private final NewsReport report = NewsReport.builder().id(17L).title("뉴스 보고서").build();

    @ParameterizedTest @EnumSource(ChannelType.class)
    void includesOnlyTwoGroundedInterpretationsAndCompleteAssumptions(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("장비 <수요> & 해석 😀", List.of("11:0"), "투자가 실제 집행되는 경우 & <전제>"),
                new Overview("두 번째 판단", List.of("11:0", "12:0"), "전체 조건"),
                new Overview("세 번째 판단", List.of("12:0"), "셋째 조건")), List.of());
        String body = render(type, 3500, List.of(finding(11), finding(12)), insight);
        assertTrue(body.contains("관점 인사이트 · 장비·소재사"));
        assertTrue(body.contains("AI 해석"));
        assertTrue(body.contains("장비 &lt;수요&gt; &amp; 해석 😀"));
        assertTrue(body.contains("투자가 실제 집행되는 경우 &amp; &lt;전제&gt;"));
        assertTrue(body.contains("두 번째 판단"));
        assertFalse(body.contains("세 번째 판단"));
        assertFalse(body.contains("숨겨진 헤드라인"));
    }

    @ParameterizedTest @EnumSource(ChannelType.class)
    void anItemWithAnyHiddenOrUnknownClaimIsCompletelyRemoved(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("숨겨진 혼합 해석", List.of("11:0", "12:0"), "숨겨진 전제"),
                new Overview("알 수 없는 해석", List.of("missing"), null),
                new Overview("허용된 해석", List.of("12:0"), "허용된 전제")), List.of());
        String body = render(type, 3500, List.of(finding(12)), insight);
        assertFalse(body.contains("숨겨진"));
        assertFalse(body.contains("알 수 없는 해석"));
        assertTrue(body.contains("허용된 해석"));
        assertTrue(body.contains("허용된 전제"));
    }

    @ParameterizedTest @EnumSource(ChannelType.class)
    void noPermittedEvidenceShowsNeutralMessageWithoutHeadlineOrIssueFallback(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("숨긴 판단", List.of("11:0"), "숨긴 전제")),
                List.of(new ReportInsightDTO.Issue(12L, "high", "대체 사유", List.of("12:0"), null, 1)));
        String body = render(type, 3500, List.of(finding(12)), insight);
        assertTrue(body.contains(NotificationInsightBrief.UNAVAILABLE));
        assertFalse(body.contains("숨긴"));
        assertFalse(body.contains("헤드라인"));
        assertFalse(body.contains("대체 사유"));
    }

    @ParameterizedTest @EnumSource(ChannelType.class)
    void emptyOverviewUsesOnlyGroundedVisibleIssueReasons(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(), List.of(
                new ReportInsightDTO.Issue(11L, "high", "제외 사유", List.of("11:0"), null, 1),
                new ReportInsightDTO.Issue(12L, "medium", "표시 사유", List.of("12:0"), null, 2),
                new ReportInsightDTO.Issue(13L, "unavailable", "근거 없는 사유", List.of(), null, 3)));
        String body = render(type, 3500, List.of(finding(12), finding(13)), insight);
        assertTrue(body.contains("표시 사유"));
        assertFalse(body.contains("제외 사유"));
        assertFalse(body.contains("근거 없는 사유"));
    }

    @Test void telegramPreservesOneValidMessageAndWholeAssumptionsAtEveryBudget() throws Exception {
        String text = "해석 😀<&> ".repeat(12);
        String assumption = "유지해야 하는 전제 😀<&> ".repeat(12);
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview(text, List.of("11:0"), assumption)), List.of());
        for (int max : List.of(12, 80, 120, 300, 600, 1200, 3500)) {
            String body = render(ChannelType.TELEGRAM, max, List.of(finding(11)), insight);
            assertTrue(body.length() <= max, "budget " + max);
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new InputSource(new StringReader("<root>" + body + "</root>")));
            String plain = org.jsoup.Jsoup.parse(body).text();
            if (plain.contains("해석 😀")) assertTrue(plain.contains(assumption.trim()), "whole assumption at " + max);
            else assertFalse(plain.contains("유지해야 하는 전제"));
        }
    }

    @Test void omittedSelectionKeepsExistingBodyExactly() {
        for (var type : ChannelType.values()) {
            var channel = channel(type, 3500);
            var view = view(List.of(finding(11)));
            assertEquals(renderer.render(report, channel, view, null), renderer.render(report, channel, view, null, null));
            assertFalse(renderer.render(report, channel, view, null).chunks().getFirst().contains("관점 인사이트"));
        }
    }

    private String render(ChannelType type, int max, List<Finding> findings, ReportInsightDTO.AudienceInsight insight) {
        var rendered = renderer.render(report, channel(type, max), view(findings), null, insight);
        assertEquals(1, rendered.chunks().size());
        return rendered.chunks().getFirst();
    }

    private ReportEventFeedbackProjection.View view(List<Finding> findings) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), false);
    }

    private Finding finding(long id) {
        return Finding.builder().id(id).summary("일반 요약")
                .article(Article.builder().id(id + 100).canonicalUrl("https://example.com/source-" + id).build()).build();
    }

    private NotificationChannel channel(ChannelType type, int max) {
        return NotificationChannel.builder().id(1L).channelType(type).maxLength(max).active(true).build();
    }
}
