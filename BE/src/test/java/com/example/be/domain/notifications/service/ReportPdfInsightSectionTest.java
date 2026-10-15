package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Axes;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Implication;
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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ReportPdfInsightSectionTest {
    @Test
    void keepsOverviewIssuesAndWatchesWithoutScoringImplicationsOrEvidenceAppendix() throws Exception {
        var insight = insight(List.of(fact(11, "grounded", List.of(0, 2))), List.of(
                        new Overview("첫 번째 종합 판단", List.of("11:0"), "첫 번째 조건"),
                        new Overview("두 번째 종합 판단", List.of("11:0"), "두 번째 조건"),
                        new Overview("세 번째 종합 판단", List.of("11:0"), "세 번째 조건")),
                List.of(new ReportInsightDTO.Issue(11L, "high", "우선 확인할 이유", List.of("11:0"),
                                new Axes(3, 2, null, null), 1),
                        new ReportInsightDTO.Issue(11L, "low", "추가 확인할 이유", List.of("11:0"),
                                new Axes(0, null, null, null), 2)),
                List.of(new Implication("조건부 영향 해석", "설비 투자에서 수요로 이어지는 경로", List.of("11:0"),
                        "예산이 확정되는 경우", "예산이 철회되는 경우")),
                List.of(new WatchItem("후속 투자 일정", "분기별 발주 규모", "발주 증가 여부", List.of("11:0"))));

        String text = render(insight, view(List.of(finding(11)), Set.of()));

        for (String expected : List.of("리포트 관점 분석 · 장비·소재사", "AI 해석", "첫 번째 종합 판단", "두 번째 종합 판단",
                "세 번째 종합 판단", "해석의 조건", "세 번째 조건", "먼저 살펴볼 이슈", "우선 확인할 이유", "추가 확인할 이유",
                "다음 확인 항목", "후속 투자 일정", "분기별 발주 규모", "발주 증가 여부", "기사 제목 11")) {
            assertTrue(text.contains(expected), expected + "\n" + text);
        }
        assertFalse(text.contains("근거 없는 헤드라인"));
        assertFalse(text.contains("내부 제공자"));
        assertFalse(text.contains("내부 모델"));
        assertFalse(text.contains("반도체 제조사"));
        assertFalse(text.contains("11:0"));
        for (String omitted : List.of("분석에 사용한 원문 근거", "참조:", "근거 1", "확인된 원문 11",
                "출처 · 전문 뉴스", "발행일", "원문 근거 문장", "https://example.com/article-11",
                "우선순위", "중요도", "직접 관련성", "영향 크기", "시급성", "새 변화", "비교 근거 없음", "3 / 3", "2 / 3",
                "관련성 낮음", "관련성 미확인", "영향 규모 미확인", "조건부 해석", "조건부 영향 해석",
                "영향 경로", "설비 투자에서 수요로 이어지는 경로", "성립 조건", "예산이 확정되는 경우", "판단 변경 조건", "예산이 철회되는 경우")) {
            assertFalse(text.contains(omitted), omitted + "\n" + text);
        }
    }

    @Test
    void sharedRejectedEvidenceCannotLeakThroughAnyDetailEvenWhenItsFindingRemainsVisible() throws Exception {
        var insight = insight(List.of(fact(11, "grounded", List.of(0)), fact(12, "grounded", List.of(0))),
                List.of(new Overview("제외된 종합 판단", List.of("11:0"), "제외된 판단 조건"),
                        new Overview("제외된 혼합 판단", List.of("11:0", "12:0"), "제외된 혼합 조건"),
                        new Overview("독립적인 판단", List.of("12:0"), "독립적인 조건")),
                List.of(new ReportInsightDTO.Issue(11L, "high", "제외된 이슈 이유", List.of("12:0"), null, 1),
                        new ReportInsightDTO.Issue(12L, "high", "제외된 혼합 이슈", List.of("11:0", "12:0"), null, 2),
                        new ReportInsightDTO.Issue(12L, "medium", "독립적인 이슈 이유", List.of("12:0"), null, 3)),
                List.of(new Implication("제외된 영향 해석", "제외된 영향 경로", List.of("11:0", "12:0"),
                                "제외된 성립 조건", "제외된 변경 조건"),
                        new Implication("독립적인 영향 해석", "독립적인 영향 경로", List.of("12:0"), "독립적인 성립 조건", "독립적인 변경 조건")),
                List.of(new WatchItem("제외된 관찰 주제", "제외된 지표", "제외된 기준", List.of("11:0", "12:0")),
                        new WatchItem("독립적인 관찰 주제", "독립적인 지표", "독립적인 기준", List.of("12:0"))));

        String text = render(insight, view(List.of(finding(11), finding(12)), Set.of(11L)));

        assertFalse(text.contains("제외된"), text);
        assertFalse(text.contains("확인된 원문 11"), text);
        assertFalse(text.contains("기사 제목 11"), text);
        for (String expected : List.of("독립적인 판단", "독립적인 조건", "독립적인 이슈 이유", "독립적인 관찰 주제",
                "독립적인 지표", "독립적인 기준", "기사 제목 12")) {
            assertTrue(text.contains(expected), expected + "\n" + text);
        }
        assertFalse(text.contains("확인된 원문 12"));
        assertFalse(text.contains("분석에 사용한 원문 근거"));
        assertFalse(text.contains("참조:"));
        assertFalse(text.contains("독립적인 영향 해석"));
        assertFalse(text.contains("독립적인 영향 경로"));
        assertFalse(text.contains("독립적인 성립 조건"));
        assertFalse(text.contains("독립적인 변경 조건"));
    }

    @Test
    void implicationsAloneShowUnavailableEvenWithVisibleGroundedEvidence() throws Exception {
        var insight = insight(List.of(fact(11, "grounded", List.of(0))), List.of(), List.of(),
                List.of(new Implication("제거할 영향 해석", "제거할 영향 경로", List.of("11:0"),
                        "제거할 성립 조건", "제거할 변경 조건")), List.of());

        String text = render(insight, view(List.of(finding(11)), Set.of()));

        assertTrue(text.contains(NotificationInsightBrief.UNAVAILABLE));
        assertFalse(text.contains("조건부 해석"));
        assertFalse(text.contains("제거할"));
        assertFalse(text.contains("분석에 사용한 원문 근거"));
    }

    @Test
    void readingBlocksKeepTheirOpeningTogetherAndSplitOversizedItemsWithoutLosingText() throws Exception {
        String longText = IntStream.range(0, 120).mapToObj(index -> "긴 문단 기록 " + index)
                .collect(Collectors.joining("\n"));
        var insight = insight(List.of(fact(11, "grounded", List.of(0))),
                List.of(new Overview("첫 묶음 해석", List.of("11:0"), "첫 묶음 조건"),
                        new Overview(longText, List.of("11:0"), "긴 문단 마지막 조건")),
                List.of(new ReportInsightDTO.Issue(11L, "high", "기사 해석 본문", List.of("11:0"), null, 1)),
                List.of(), List.of(new WatchItem("마지막 관찰 주제", "마지막 지표", "마지막 기준", List.of("11:0"))));
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            pdf.paragraph("앞 페이지 내용");
            pdf.gap(620);
            ReportPdfInsightSection.write(pdf, insight, view(List.of(finding(11)), Set.of()));
            bytes = pdf.save();
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            assertTrue(document.getNumberOfPages() > 3);
            PDFTextStripper page = new PDFTextStripper();
            page.setStartPage(1);
            page.setEndPage(1);
            assertFalse(page.getText(document).contains("리포트 관점 분석"));
            page.setStartPage(2);
            page.setEndPage(2);
            String opening = page.getText(document);
            assertTrue(opening.contains("리포트 관점 분석"));
            assertTrue(opening.contains("첫 묶음 해석"));
            assertTrue(opening.contains("해석의 조건\n첫 묶음 조건"));
            String text = new PDFTextStripper() {
                @Override protected void processTextPosition(TextPosition position) {
                    assertTrue(position.getXDirAdj() >= 40);
                    assertTrue(position.getXDirAdj() + position.getWidthDirAdj() <= PDRectangle.A4.getWidth() - 40);
                    assertTrue(position.getYDirAdj() > 20 && position.getYDirAdj() < 820);
                    super.processTextPosition(position);
                }
            }.getText(document);
            for (int index = 0; index < 120; index++) assertTrue(text.contains("긴 문단 기록 " + index));
            assertTrue(text.contains("긴 문단 마지막 조건"));
            assertTrue(text.contains("기사\n기사 제목 11\n해석\n기사 해석 본문"));
            assertTrue(text.contains("확인할 지표\n마지막 지표"));
            assertTrue(text.contains("판단 기준\n마지막 기준"));
        }
    }

    @Test
    void missingMismatchedUngroundedOrSentenceLessEvidenceNeverValidatesSavedInterpretations() throws Exception {
        var facts = List.of(fact(11, "grounded", List.of(0)),
                new ReportInsightDTO.Fact("12:0", "다른 기사 주장", "FACT", null, 12L, 999L, List.of(0), "grounded"),
                fact(13, "ungrounded", List.of(0)), fact(14, "grounded", List.of()), fact(15, "grounded", List.of(-1)));
        var insight = insight(facts, List.of(
                        new Overview("보이지 않는 기사 해석", List.of("11:0"), "숨긴 조건"),
                        new Overview("다른 기사 해석", List.of("12:0"), "숨긴 조건"),
                        new Overview("검증되지 않은 해석", List.of("13:0"), "숨긴 조건"),
                        new Overview("근거 문장 없는 해석", List.of("14:0"), "숨긴 조건"),
                        new Overview("잘못된 문장 번호 해석", List.of("15:0"), "숨긴 조건"),
                        new Overview("존재하지 않는 주장 해석", List.of("unknown"), "숨긴 조건")),
                List.of(), List.of(new Implication("해석 누출", "경로 누출", List.of(), "조건 누출", "반증 누출")),
                List.of(new WatchItem("관찰 누출", "지표 누출", "기준 누출", List.of("unknown"))));

        String text = render(insight, view(List.of(finding(12), finding(13), finding(14), finding(15)), Set.of()));

        assertTrue(text.contains(NotificationInsightBrief.UNAVAILABLE));
        for (String hidden : List.of("기사 해석", "검증되지 않은", "문장 없는", "잘못된", "존재하지 않는", "숨긴", "누출", "확인된 원문", "다른 기사 주장")) {
            assertFalse(text.contains(hidden), hidden + "\n" + text);
        }
    }

    @Test
    void factsAloneShowUnavailableWithoutRenderingEvidenceOrAttribution() throws Exception {
        var insight = insight(List.of(
                        new ReportInsightDTO.Fact("11:1", "증설을 계획하고 있다", "FORECAST", null, 11L, 111L, List.of(1), "grounded"),
                        new ReportInsightDTO.Fact("11:2", "수요가 확대될 수 있다", "OPINION", "회사 관계자", 11L, 111L, List.of(2), "grounded")),
                List.of(), List.of(), List.of(), List.of());

        String text = render(insight, view(List.of(finding(11)), Set.of()));

        assertTrue(text.contains(NotificationInsightBrief.UNAVAILABLE));
        for (String omitted : List.of("분석에 사용한 원문 근거", "참조:", "증설을 계획하고 있다",
                "수요가 확대될 수 있다", "회사 관계자", "근거 1", "근거 2")) {
            assertFalse(text.contains(omitted), omitted + "\n" + text);
        }
    }

    @Test
    void omittedAudienceSelectionProducesNoSection() throws Exception {
        ReportPdfLayout pdf = mock(ReportPdfLayout.class);
        ReportPdfInsightSection.write(pdf, null, view(List.of(), Set.of()));
        verifyNoInteractions(pdf);
    }

    private static String render(ReportInsightDTO.AudienceInsight insight, ReportEventFeedbackProjection.View view) throws Exception {
        byte[] bytes;
        try (PDDocument document = new PDDocument(); ReportPdfLayout pdf = new ReportPdfLayout(document)) {
            ReportPdfInsightSection.write(pdf, insight, view);
            bytes = pdf.save();
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static ReportInsightDTO.AudienceInsight insight(List<ReportInsightDTO.Fact> facts,
            List<Overview> overview, List<ReportInsightDTO.Issue> issues, List<Implication> implications, List<WatchItem> watches) {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "근거 없는 헤드라인", "high",
                overview, issues, facts, implications, watches, "내부 제공자", "내부 모델", null);
    }

    private static ReportInsightDTO.Fact fact(long id, String groundedness, List<Integer> sentenceIds) {
        return new ReportInsightDTO.Fact(id + ":0", "확인된 원문 " + id, "FACT", null, id, 100 + id, sentenceIds, groundedness);
    }

    private static Finding finding(long id) {
        return Finding.builder().id(id).article(Article.builder().id(100 + id).title("기사 제목 " + id)
                .canonicalUrl("https://example.com/article-" + id).sourceName("전문 뉴스")
                .publishedAt(OffsetDateTime.parse("2026-10-15T09:00:00+09:00")).build()).build();
    }

    private static ReportEventFeedbackProjection.View view(List<Finding> findings, Set<Long> excluded) {
        return new ReportEventFeedbackProjection.View(findings, new ReportReadingContent("", null), !excluded.isEmpty(), excluded);
    }
}
