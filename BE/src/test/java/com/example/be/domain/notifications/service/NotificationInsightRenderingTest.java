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
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class NotificationInsightRenderingTest {
    private final NotificationRenderer renderer = new NotificationRenderer(mock(FindingRepository.class),
            TopicRelevanceTestSupport.legacyPolicy(), mock(ReportEventFeedbackProjection.class), mock(NotificationDartSummary.class));
    private final NewsReport report = NewsReport.builder().id(17L).title("뉴스 보고서").build();

    @ParameterizedTest @EnumSource(ChannelType.class)
    void includesOnlyTwoGroundedInterpretationsAndKeepsTheirAssumptions(ChannelType type) {
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
    void sharedEvidenceExclusionVetoesWholeInterpretationButPreservesIndependentInsight(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("공유 근거 해석", List.of("11:0"), "공유 근거 전제"),
                new Overview("혼합 근거 해석", List.of("11:0", "12:0"), "혼합 근거 전제"),
                new Overview("독립 근거 해석", List.of("12:0"), "독립 근거 전제")), List.of());
        var view = new ReportEventFeedbackProjection.View(List.of(finding(11), finding(12)),
                new ReportReadingContent("", null), true, Set.of(11L));
        String body = renderer.render(report, channel(type, 3500), view, null, insight).chunks().getFirst();
        assertFalse(body.contains("공유 근거"));
        assertFalse(body.contains("혼합 근거"));
        assertTrue(body.contains("독립 근거 해석"));
        assertTrue(body.contains("독립 근거 전제"));
    }

    @ParameterizedTest @EnumSource(ChannelType.class)
    void sharedEvidenceExclusionAlsoVetoesGroundedIssueFallback(ChannelType type) {
        var insight = NotificationInsightTestSupport.insight(List.of(), List.of(
                new ReportInsightDTO.Issue(11L, "high", "공유 근거 대체 사유", List.of("11:0"), null, 1),
                new ReportInsightDTO.Issue(12L, "high", "혼합 근거 대체 사유", List.of("11:0", "12:0"), null, 2),
                new ReportInsightDTO.Issue(12L, "medium", "독립 근거 대체 사유", List.of("12:0"), null, 3)));
        var view = new ReportEventFeedbackProjection.View(List.of(finding(11), finding(12)),
                new ReportReadingContent("", null), true, Set.of(11L));
        String body = renderer.render(report, channel(type, 3500), view, null, insight).chunks().getFirst();
        assertFalse(body.contains("공유 근거 대체 사유"));
        assertFalse(body.contains("혼합 근거 대체 사유"));
        assertTrue(body.contains("독립 근거 대체 사유"));
    }

    @Test void emailShowsInterpretationAndConditionWithoutSourceQuotationOrMarkdownBreak() {
        String interpretation = "여러 금융사 침해에서 공통 IP와 유사 수법이 확인돼 네트워크 차단·탐지 검토가 필요합니다.";
        String second = "해외 서비스 도입이 확대될 경우 배포·연동 준비가 필요할 수 있습니다.";
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("원문: 「공통 IP가 일부 발견됐다는 원문 문장입니다.」 해석: " + interpretation + "\\\n",
                        List.of("11:0"), "동일 공격군 또는 공유 인프라의 연속적 이용을 시사합니다."),
                new Overview("원문: 「해외 콘텐츠 도입 확대 발표입니다.」 해석: " + second,
                        List.of("12:0"), "별도로 저장한 조건")), List.of());
        String body = render(ChannelType.EMAIL, 3500, List.of(finding(11), finding(12)), insight);
        assertTrue(body.contains(interpretation));
        assertTrue(body.contains(second));
        assertFalse(body.contains("원문: 「"));
        assertFalse(body.contains("해석:"));
        assertFalse(body.contains("전제:"));
        assertFalse(body.contains("공통 IP가 일부 발견됐다는 원문 문장"));
        assertTrue(body.contains("동일 공격군"));
        assertTrue(body.contains("별도로 저장한 조건"));
        assertFalse(body.contains("\\"));
    }

    @Test void emailKeepsInterpretationPunctuationAndInternalBackslashesAfterNestedSourceQuote() {
        String interpretation = "「운영 해석: 조건부」 지침은 C:\\ops 경로의 <설정> & 로그를 확인할 경우에만 적용할 수 있습니다.";
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("원문: 「기사 속 「용어」 해석: 내부 설명입니다.」 해석: " + interpretation,
                        List.of("11:0"), null),
                new Overview("원문 인용 없는 기존 판단은 유지합니다.", List.of("12:0"), null)), List.of());
        String body = render(ChannelType.EMAIL, 3500, List.of(finding(11), finding(12)), insight);
        assertTrue(body.contains("「운영 해석: 조건부」 지침은 C:\\ops 경로의 &lt;설정&gt; &amp; 로그를 확인할 경우에만 적용할 수 있습니다."));
        assertTrue(body.contains("원문 인용 없는 기존 판단은 유지합니다."));
        assertFalse(body.contains("기사 속"));
    }

    @Test void emailAlsoRemovesSourceWrapperFromGroundedIssueFallback() {
        var insight = NotificationInsightTestSupport.insight(List.of(), List.of(
                new ReportInsightDTO.Issue(11L, "high", "원문: 「원문 근거 문장」 해석: 설비 일정 확인이 필요할 수 있습니다.",
                        List.of("11:0"), null, 1)));
        String body = render(ChannelType.EMAIL, 3500, List.of(finding(11)), insight);
        assertTrue(body.contains("설비 일정 확인이 필요할 수 있습니다."));
        assertFalse(body.contains("원문 근거 문장"));
        assertFalse(body.contains("해석:"));
    }

    @Test void telegramStillIncludesSourceQuotationAndAssumption() {
        var insight = NotificationInsightTestSupport.insight(List.of(
                new Overview("원문: 「검증된 원문 문장」 해석: 조건부 해석입니다.", List.of("11:0"), "필요한 전제입니다.")), List.of());
        String body = render(ChannelType.TELEGRAM, 3500, List.of(finding(11)), insight);
        assertTrue(body.contains("원문: 「검증된 원문 문장」 해석: 조건부 해석입니다."));
        assertTrue(body.contains("전제: 필요한 전제입니다."));
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
