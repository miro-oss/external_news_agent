package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.WatchItem;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.reports.insight.ReportInsightDTO;
import com.example.be.domain.reports.service.ReportEventFeedbackProjection;
import com.example.be.domain.reports.service.ReportReadingContent;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportPdfInsightSectionTest {
    @Test
    void compactOverviewKeepsTheFirstCompleteInterpretationConditionAndObservation() throws Exception {
        var insight = insight(List.of(fact(11), fact(12)), List.of(
                        new Overview("첫 번째 종합 판단", List.of("11:0"), "첫 번째 조건"),
                        new Overview("두 번째 종합 판단", List.of("12:0"), "두 번째 조건")),
                List.of(new WatchItem("후속 투자 일정", "분기별 발주 규모", "발주 증가 여부", List.of("11:0")),
                        new WatchItem("두 번째 확인점", "두 번째 지표", "두 번째 기준", List.of("12:0"))));
        String text = render(insight, view(Set.of()));
        for (String expected : List.of("관점 한눈에 · 장비·소재사", "확인된 사실", "확인된 원문 11",
                "업무상 의미", "첫 번째 종합 판단", "해석의 조건", "첫 번째 조건", "다음 확인점",
                "후속 투자 일정", "분기별 발주 규모", "발주 증가 여부", "전체 관점 분석은 보고서 화면에서"))
            assertTrue(text.contains(expected), expected + "\n" + text);
        for (String hidden : List.of("두 번째", "확인된 원문 12", "리포트 관점 분석", "뒤쪽 관점 분석", "내부 모델"))
            assertFalse(text.contains(hidden), hidden + "\n" + text);
    }

    @Test
    void hiddenEvidenceVetoesWholeInterpretationsBeforeCompactSelection() throws Exception {
        var insight = insight(List.of(fact(11), fact(12)), List.of(
                        new Overview("제외된 판단", List.of("11:0"), "제외된 조건"),
                        new Overview("혼합된 판단", List.of("11:0", "12:0"), "혼합된 조건"),
                        new Overview("독립 판단", List.of("12:0"), "독립 조건")),
                List.of(new WatchItem("제외된 확인점", "제외된 지표", "제외된 기준", List.of("11:0")),
                        new WatchItem("독립 확인점", "독립 지표", "독립 기준", List.of("12:0"))));
        String text = render(insight, view(Set.of(11L)));
        for (String hidden : List.of("제외된", "혼합된", "확인된 원문 11")) assertFalse(text.contains(hidden));
        for (String expected : List.of("독립 판단", "독립 조건", "독립 확인점", "독립 지표", "독립 기준"))
            assertTrue(text.contains(expected));
    }

    @Test
    void sourceWrappersAreRemovedWithoutChangingNestedProseOrConditions() throws Exception {
        var insight = insight(List.of(fact(11)), List.of(new Overview(
                        "원문: 「회사 측은 「검토 중」이라고 밝혔다.」 해석: 실제 발주를 확인해야 합니다.",
                        List.of("11:0"), "원문: 「일정 미정」 미확인 가정: 투자가 집행되는 경우")),
                List.of(new WatchItem("원문: 「검토 중」 관찰 항목: 공식 투자 일정",
                        "원문: 「규모 공개」 해석: 분기별 발주 금액", "원문: 「조건부 투자」 반증 조건: 발주 취소 여부",
                        List.of("11:0"))));
        String text = render(insight, view(Set.of()));
        assertFalse(text.contains("원문: 「"));
        for (String expected : List.of("실제 발주를 확인해야 합니다.", "투자가 집행되는 경우", "공식 투자 일정",
                "분기별 발주 금액", "발주 취소 여부")) assertTrue(text.contains(expected));
    }

    @Test
    void oversizedInterpretationsAreOmittedWholeWithAWorkingReadingDestination() throws Exception {
        String longText = "계획이 확정되지 않은 조건과 후속 확인 범위에 대한 긴 설명입니다. ".repeat(20);
        var insight = insight(List.of(fact(11)), List.of(new Overview(longText, List.of("11:0"), "긴 설명의 조건")), List.of());
        String text = render(insight, view(Set.of()));
        assertFalse(text.contains("긴 설명의 조건"));
        assertFalse(text.contains("계획이 확정되지 않은"));
        assertTrue(text.contains("전체 관점 분석은 보고서 화면에서 확인하세요."));
        assertFalse(text.contains("뒤쪽"));
    }

    @Test
    void invalidSourceIdentityAndUngroundedClaimsCannotValidateInterpretations() throws Exception {
        var invalid = new ReportInsightDTO.Fact("11:0", "다른 기사 주장", "FACT", null,
                11L, 999L, List.of(0), "grounded");
        var ungrounded = new ReportInsightDTO.Fact("12:0", "미검증 주장", "FACT", null,
                12L, 112L, List.of(0), "ungrounded");
        String text = render(insight(List.of(invalid, ungrounded), List.of(
                new Overview("숨긴 해석", List.of("11:0"), "숨긴 조건"),
                new Overview("미검증 해석", List.of("12:0"), "미검증 조건")), List.of()), view(Set.of()));
        assertTrue(text.contains(NotificationInsightBrief.UNAVAILABLE));
        assertFalse(text.contains("숨긴"));
        assertFalse(text.contains("미검증"));
    }

    @Test
    void noSelectedAudienceProducesNoSection() throws Exception {
        var pdf = mock(ReportPdfLayout.class);
        ReportPdfInsightSection.brief(pdf, null, view(Set.of()));
        verifyNoInteractions(pdf);
    }

    private static String render(ReportInsightDTO.AudienceInsight insight, ReportEventFeedbackProjection.View view) throws Exception {
        byte[] bytes;
        try (var document = new PDDocument(); var pdf = new ReportPdfLayout(document)) {
            ReportPdfInsightSection.brief(pdf, insight, view);
            bytes = pdf.save();
        }
        try (var document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertTrue(position.getXDirAdj() >= 40);
                    assertTrue(position.getXDirAdj() + position.getWidthDirAdj() <= 554);
                    assertTrue(position.getYDirAdj() > 20 && position.getYDirAdj() < 820);
                    super.processTextPosition(position);
                }
            }.getText(document);
        }
    }

    private static ReportInsightDTO.AudienceInsight insight(List<ReportInsightDTO.Fact> facts,
            List<Overview> overview, List<WatchItem> watches) {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "헤드라인", "high",
                overview, List.of(), facts, List.of(), watches, "내부 제공자", "내부 모델", null);
    }

    private static ReportInsightDTO.Fact fact(long id) {
        return new ReportInsightDTO.Fact(id + ":0", "확인된 원문 " + id, "FACT", null, id, 100 + id, List.of(0), "grounded");
    }

    private static ReportEventFeedbackProjection.View view(Set<Long> excluded) {
        var findings = List.of(11L, 12L).stream().map(id -> Finding.builder().id(id)
                .article(Article.builder().id(100 + id).build()).build()).toList();
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), !excluded.isEmpty(), excluded);
    }
}
