package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.Overview;
import com.example.be.domain.analysis.agent.dto.AgentReportInsightResponse.WatchItem;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.Article;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

class ReportInsightBriefingTest {
    @Test
    void preservesForecastAttributionAndConditionsAndOnlySelectsReferencedFacts() {
        var forecast = fact(1, "회사는 생산량이 늘어날 것으로 예상했습니다.", "FORECAST", null);
        var opinion = fact(2, "회복할 수 있다는 견해입니다.", "OPINION", "분석가");
        var unused = fact(3, "선택한 판단과 무관한 저장 주장입니다.", "FACT", null);
        var source = insight(List.of(
                new Overview("수요를 확인할 필요가 있습니다.", List.of("1:0"), "계획이 실제로 집행되는 경우"),
                new Overview("확정적인 회복으로 판단하지 않습니다.", List.of("2:0"), "해당 의견을 해석하는 경우")),
                List.of(new WatchItem("집행", "후속 집행 발표", "집행이 확인되면 판단 재검토", List.of("1:0"))),
                List.of(forecast, opinion, unused));

        var result = source.briefing();
        assertEquals(List.of(forecast, opinion), result.facts());
        assertEquals(source.overview(), result.overview());
        assertEquals(source.watchItems(), result.watchItems());
        assertEquals(0, result.omittedItemCount());
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(source));
        assertEquals(ReportInsightBriefing.VERSION, json.get("briefing").get("version").asText());
        assertEquals(3, json.get("facts").size());
    }

    @Test
    void requiresEveryClaimAndExactFinalFindingArticlePairWithoutCountingHiddenMaterial() {
        var source = insight(List.of(
                new Overview("두 기사에 근거한 판단", List.of("1:0", "2:0"), "공통 조건"),
                new Overview("남은 기사 판단", List.of("1:0"), null),
                new Overview("알 수 없는 claim", List.of("1:99"), null)), List.of(),
                List.of(fact(1, "주장 1", "FACT", null), fact(2, "주장 2", "FACT", null)));
        var visible = List.of(finding(1, 101), finding(2, 999));
        var result = ReportInsightBriefing.from(source, visible, Set.of());
        assertEquals(List.of("남은 기사 판단"), result.overview().stream().map(Overview::text).toList());
        assertEquals(List.of("1:0"), result.facts().stream().map(ReportInsightDTO.Fact::id).toList());
        assertEquals(0, result.omittedItemCount());
        assertEquals(ReportInsightBriefing.empty(), ReportInsightBriefing.from(source, visible, Set.of(1L)));
    }

    @Test
    void rejectsMalformedGroundingEvidenceAndClaimIdentity() {
        var good = fact(1, "주장", "FACT", null);
        var invalid = List.of(
                new ReportInsightDTO.Fact("2:0", "주장", "FACT", null, 1L, 101L, List.of(0), "grounded"),
                new ReportInsightDTO.Fact("1:00", "주장", "FACT", null, 1L, 101L, List.of(0), "grounded"),
                new ReportInsightDTO.Fact("1:0", "주장", "FACT", null, 1L, 101L, List.of(), "grounded"),
                new ReportInsightDTO.Fact("1:0", "주장", "FACT", null, 1L, 101L, List.of(0, 0), "grounded"),
                new ReportInsightDTO.Fact("1:0", "주장", "FACT", null, 1L, 101L, List.of(-1), "grounded"),
                new ReportInsightDTO.Fact("1:0", "주장", "FACT", null, 1L, 101L, List.of(0), "weak"),
                new ReportInsightDTO.Fact("1:0", "견해", "OPINION", null, 1L, 101L, List.of(0), "grounded"));
        for (var fact : invalid) {
            var source = insight(List.of(new Overview("해석", List.of(fact.id()), null)), List.of(), List.of(fact));
            assertEquals(ReportInsightBriefing.empty(), source.briefing());
        }
        var duplicate = insight(List.of(new Overview("해석", List.of("1:0"), null)), List.of(), List.of(good, good));
        assertEquals(ReportInsightBriefing.empty(), duplicate.briefing());
    }

    @Test
    void removesOnlyBalancedLeadingQuoteWithExactFieldMarkerAndKeepsTailWhole() {
        String text = "조건이 충족되더라도 양산 확정을 뜻하지 않습니다.";
        String quote = "원문: 「회사는 「계획」을 발표했습니다.」 해석: " + text;
        var source = insight(List.of(new Overview(quote, List.of("1:0"),
                "원문: 「조건 설명」 미확인 가정: 계획이 집행되는 경우")),
                List.of(new WatchItem("원문: 「주제 원문」 관찰 항목: 생산 계획", "원문: 「지표 원문」 해석: 계획 발표",
                        "원문: 「조건 원문」 반증 조건: 계획 변경이 확인되는 경우", List.of("1:0"))), List.of(fact(1, "주장", "FACT", null)));
        assertEquals(text, source.briefing().overview().getFirst().text());
        assertEquals("계획이 집행되는 경우", source.briefing().overview().getFirst().assumption());
        assertEquals(quote, source.overview().getFirst().text());
        assertEquals(new WatchItem("생산 계획", "계획 발표", "계획 변경이 확인되는 경우", List.of("1:0")),
                source.briefing().watchItems().getFirst());
        for (String malformed : List.of("원문: 「열린 인용 해석: 판단", "원문: 「인용」 관찰 항목: 판단",
                "원문: 「인용」 해석: ", "앞 문장. 원문: 「인용」 해석: 뒤 판단", "원문: 「」 해석: 판단")) {
            assertEquals(malformed, ReportInsightBriefing.unquotedProse(malformed, "해석"));
        }
        assertEquals("본문\\중간", ReportInsightBriefing.unquotedProse("본문\\중간\\\n", "해석"));
    }

    @Test
    void neverTruncatesLongNegationConditionsOrTriggersAndCountsWholeBundles() {
        String longTail = "가".repeat(170) + "그러나 실행을 확정한 것은 아닙니다.";
        var source = insight(List.of(new Overview(longTail, List.of("1:0"), null),
                new Overview("조건이 긴 판단", List.of("1:0"), "가".repeat(141)),
                new Overview("😀".repeat(180), List.of("1:0"), null),
                new Overview("둘째", List.of("1:0"), null),
                new Overview("셋째", List.of("1:0"), null)),
                List.of(new WatchItem("확인", "지표", "가".repeat(141), List.of("1:0"))),
                List.of(fact(1, "원문".repeat(100), "FACT", null)));
        var result = source.briefing();
        assertEquals(2, result.overview().size());
        assertEquals("😀".repeat(180), result.overview().getFirst().text());
        assertEquals(4, result.omittedItemCount());
        assertTrue(result.facts().isEmpty());
        assertTrue(result.watchItems().isEmpty());
        assertEquals(longTail, source.overview().getFirst().text());
    }

    @Test
    void currentWatchQuotationLabelsKeepCompleteObservationProse() {
        var watch = new WatchItem("원문: 「내용」 관찰 항목: 생산", "원문: 「내용」 관찰 항목: 생산 계획",
                "원문: 「내용」 관찰 항목: 계획이 확인되면 재검토", List.of("1:0"));
        var source = insight(List.of(), List.of(watch), List.of(fact(1, "주장", "FACT", null)));
        assertEquals(new WatchItem("생산", "생산 계획", "계획이 확인되면 재검토", List.of("1:0")),
                source.briefing().watchItems().getFirst());
    }

    @Test
    void fallbackIsOnlyForOriginallyEmptyOverviewAndSupportedIssueReason() {
        var issue = new ReportInsightDTO.Issue(1L, "high", "저장된 이슈 판단", List.of("1:0"), null, 1);
        var base = insight(List.of(), List.of(), List.of(fact(1, "주장", "FACT", null)));
        var empty = withIssues(base, List.of(issue));
        assertEquals("저장된 이슈 판단", empty.briefing().overview().getFirst().text());
        assertNull(empty.briefing().overview().getFirst().assumption());
        var filtered = withIssues(insight(List.of(new Overview("숨겨질 판단", List.of("2:0"), null)),
                List.of(), base.facts()), List.of(issue));
        assertEquals(ReportInsightBriefing.empty(), filtered.briefing());
    }

    @Test
    void longLegacyIssueReasonDoesNotPromiseAnOmittedPdfDetail() {
        var issues = List.of(new ReportInsightDTO.Issue(1L, "high", "가".repeat(181), List.of("1:0"), null, 1),
                new ReportInsightDTO.Issue(1L, "high", "짧은 저장 판단", List.of("1:0"), null, 2));
        var source = withIssues(insight(List.of(), List.of(), List.of(fact(1, "주장", "FACT", null))), issues);
        assertEquals(List.of("짧은 저장 판단"), source.briefing().overview().stream().map(Overview::text).toList());
        assertEquals(0, source.briefing().omittedItemCount());
    }

    private ReportInsightDTO.AudienceInsight withIssues(ReportInsightDTO.AudienceInsight source, List<ReportInsightDTO.Issue> issues) {
        return new ReportInsightDTO.AudienceInsight(source.audience(), source.headline(), source.importance(), source.overview(),
                issues, source.facts(), source.implications(), source.watchItems(), "test", "test", null);
    }

    private ReportInsightDTO.AudienceInsight insight(List<Overview> overview, List<WatchItem> watch, List<ReportInsightDTO.Fact> facts) {
        return new ReportInsightDTO.AudienceInsight(Audience.EQUIPMENT_MAKER, "자유 헤드라인은 사용하지 않습니다.", "high",
                overview, List.of(), facts, List.of(), watch, "test", "test", null);
    }

    private ReportInsightDTO.Fact fact(long id, String text, String type, String attributedTo) {
        return new ReportInsightDTO.Fact(id + ":0", text, type, attributedTo, id, id + 100, List.of(0), "grounded");
    }

    private Finding finding(long id, long articleId) {
        return Finding.builder().id(id).article(Article.builder().id(articleId).build()).build();
    }
}
