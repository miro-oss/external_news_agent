package com.example.be.domain.notifications.service;

import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.relevance.TopicRelevanceTestSupport;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportContent;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationRendererTest {
    private final TopicRelevancePolicy relevancePolicy = TopicRelevanceTestSupport.legacyPolicy();

    private final FindingRepository findingRepository = mock(FindingRepository.class);
    private final NotificationRenderer renderer = new NotificationRenderer(findingRepository, relevancePolicy);

    @Test
    void topicRejectedFindingCannotReachEitherNotificationChannelThroughSavedDigest() {
        Finding rejected = finding();
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(rejected));
        when(relevancePolicy.filterFindings(List.of(rejected))).thenReturn(List.of());
        NewsReport report = report("## 오늘의 핵심\n토스 공정위 무관 요약");
        org.springframework.test.util.ReflectionTestUtils.setField(report, "structuredContent",
                new com.example.be.domain.reports.entity.ReportContent(
                        List.of("토스 공정위 무관 요약"), List.of(), List.of(), List.of()));
        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertFalse(body.contains("토스"));
            assertFalse(body.contains("검증된 핵심 요약"));
            assertFalse(body.contains("https://example.com/article"));
        }
        assertTrue(report.getMarkdownBody().contains("토스"));
    }

    @Test
    void dailyNotificationUsesOnlySavedFindingsWithoutRunId() {
        NewsReport daily = NewsReport.builder().id(17L)
                .reportScope(com.example.be.domain.reports.entity.ReportScope.DAILY)
                .title("일일 보고서").reflectedFindingIds(List.of(1L))
                .generatedAt(LocalDateTime.of(2026, 9, 4, 0, 5)).build();
        when(findingRepository.findForReportByIdIn(List.of(1L))).thenReturn(List.of(finding()));
        for (ChannelType type : ChannelType.values()) {
            assertTrue(renderer.render(daily, channel(type, 3500)).chunks().getFirst().contains("검증된 핵심 요약"));
        }
        org.mockito.Mockito.verify(findingRepository, org.mockito.Mockito.never())
                .findForReportByRunId(org.mockito.ArgumentMatchers.any());
    }

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
    void telegramKeepsOneConciseMessageAtConfiguredSafeLength() {
        NewsReport report = report("# 내부 보고서");
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding(), finding()));

        RenderedNotification rendered = renderer.render(report, channel(ChannelType.TELEGRAM, 120));

        org.junit.jupiter.api.Assertions.assertEquals(1, rendered.chunks().size());
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

    @Test
    void decodesLegacyEntitiesWithoutTurningTextIntoMarkup() {
        Finding finding = Finding.builder().id(1L).article(Article.builder().id(1L)
                .title("억대 연봉&middot;자사주까지&hellip;").canonicalUrl("https://example.com/article")
                .fetchStatus(com.example.be.domain.collection.entity.FetchStatus.FULLTEXT).body("확보한 본문").build())
                .summary("연봉&amp;middot;자사주&hellip; &lsquo;인재 경쟁&rsquo; &lt;b&gt;문자&lt;/b&gt;").build();
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding));
        String body = renderer.render(report(""), channel(ChannelType.TELEGRAM, 3500)).chunks().getFirst();
        assertTrue(body.contains("연봉·자사주… ‘인재 경쟁’"));
        assertTrue(body.contains("&lt;b&gt;문자&lt;/b&gt;"));
        assertFalse(body.contains("&amp;middot;"));
        assertFalse(body.contains("<b>문자</b>"));
    }

    @Test
    void structuredReportSummaryWinsOverIndividualArticles() {
        NewsReport report = report("## 요약\n과거 요약");
        var content = new com.example.be.domain.reports.entity.ReportContent(
                List.of("오늘 보고서의 결론입니다."), List.of(), List.of(), List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(report, "structuredContent", content);
        when(findingRepository.findForReportByRunId(42L)).thenReturn(java.util.Collections.nCopies(30, finding()));
        for (ChannelType type : ChannelType.values()) {
            var message = renderer.render(report, channel(type,3500));
            org.junit.jupiter.api.Assertions.assertEquals(1,message.chunks().size());
            String body = message.chunks().getFirst();
            assertTrue(body.contains("오늘 보고서의 결론입니다."));
            assertFalse(body.contains("검증된 핵심 요약"));
            assertFalse(body.contains("HBM 공급 계약 확대"));
            assertTrue(body.length()<1500);
        }
    }

    @Test
    void neitherChannelSendsHiddenFindingLinksOrStoredSummaryClaims() {
        Finding available = finding();
        Finding hidden = Finding.builder().id(2L)
                .article(Article.builder().id(2L).title("숨길 기사")
                        .fetchStatus(com.example.be.domain.collection.entity.FetchStatus.METADATA_ONLY)
                        .summary("요약만 있음").canonicalUrl("https://example.com/hidden").build())
                .summary("숨길 기사 요약").build();
        NewsReport report = report("## 오늘의 핵심\n숨길 과거 요약");
        var stored = new com.example.be.domain.reports.entity.ReportContent(
                List.of("숨길 과거 요약"), List.of(), List.of(), List.of());
        org.springframework.test.util.ReflectionTestUtils.setField(report, "structuredContent", stored);
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(hidden, available));
        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertFalse(body.contains("숨길"));
            assertFalse(body.contains("https://example.com/hidden"));
            assertTrue(body.contains("검증된 핵심 요약"));
            assertTrue(body.contains("https://example.com/article"));
        }
        org.junit.jupiter.api.Assertions.assertEquals(stored, report.getStructuredContent());
    }

    @Test
    void eventCardsAssociateWatchesAndSourcesByFindingIdDespiteOppositeRepositoryOrder() {
        Finding first = finding(11L, 101L, "첫 기사", "https://example.com/first");
        Finding second = finding(22L, 202L, "둘째 기사", "https://example.com/second");
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(second, first));
        NewsReport report = structuredReport(new ReportContent(List.of("저장된 보고서의 핵심 결론"),
                List.of(event("첫 번째 사건", "첫 사건 주요 내용", "첫 사건 읽는 관점 비밀", 11L),
                        event("두 번째 사건", "둘째 사건 주요 내용", "둘째 사건 중요도 비밀", 22L)),
                List.of(watch("둘째 확인 주제", "둘째 확인 이유", 22L),
                        watch("첫 확인 주제", "첫 확인 이유", 11L)), List.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(renderer, "publicBaseUrl", "https://news.example.com");

        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            int firstOffset = body.indexOf("첫 번째 사건");
            int secondOffset = body.indexOf("두 번째 사건");
            assertTrue(firstOffset >= 0 && secondOffset > firstOffset, body);
            String firstCard = body.substring(firstOffset, secondOffset);
            String secondCard = body.substring(secondOffset);
            assertTrue(firstCard.contains("첫 사건 주요 내용"));
            assertTrue(firstCard.contains("첫 확인 주제"));
            assertTrue(firstCard.contains("첫 확인 이유"));
            assertTrue(firstCard.contains("https://example.com/first"));
            assertFalse(firstCard.contains("둘째 확인"));
            assertFalse(firstCard.contains("https://example.com/second"));
            assertTrue(secondCard.contains("둘째 사건 주요 내용"));
            assertTrue(secondCard.contains("둘째 확인 주제"));
            assertTrue(secondCard.contains("둘째 확인 이유"));
            assertTrue(secondCard.contains("https://example.com/second"));
            assertFalse(secondCard.contains("첫 확인"));
            assertFalse(body.contains("읽는 관점"));
            assertFalse(body.contains("중요도 비밀"));
            assertTrue(body.contains("https://news.example.com/#/reports?reportId=17"));
            assertTrue(body.contains("보고서 전체 보기"));
            if (type == ChannelType.EMAIL) {
                assertTrue(body.contains("저장된 보고서의 핵심 결론"));
                assertTrue(body.contains("주요 내용"));
                assertTrue(body.contains("후속 확인"));
            } else {
                assertTrue(body.contains("확인할 점"));
            }
        }
    }

    @Test
    void eventsAndWatchesRequireEveryReferencedFindingAndNeverResolveArticleIdsOrPositions() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(
                finding(11L, 101L, "유효 기사", "https://example.com/valid"),
                finding(22L, 202L, "무관 기사", "https://example.com/unrelated")));
        NewsReport report = structuredReport(new ReportContent(List.of("검증된 보고서 결론"),
                List.of(event("유효 사건", "유효 사건 내용", "", 11L),
                        event("없는 ID 사건", "표시 금지", "", 999L),
                        event("기사 ID 사건", "표시 금지", "", 101L),
                        event("배열 위치 사건", "표시 금지", "", 0L),
                        event("일부만 유효 사건", "표시 금지", "", 11L, 999L),
                        event("근거 없는 사건", "표시 금지", "")),
                List.of(watch("유효 확인 주제", "유효 확인 이유", 11L),
                        watch("일부만 유효 확인", "표시 금지", 11L, 999L),
                        watch("기사 ID 확인", "표시 금지", 101L),
                        watch("근거 없는 확인", "표시 금지"),
                        watch("다른 사건의 확인", "표시 금지", 22L)), List.of()));

        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertTrue(body.contains("유효 사건 내용"));
            assertTrue(body.contains("유효 확인 주제"));
            assertTrue(body.contains("유효 확인 이유"));
            assertTrue(body.contains("https://example.com/valid"));
            for (String unsupported : List.of("없는 ID 사건", "기사 ID 사건", "배열 위치 사건", "일부만 유효 사건",
                    "근거 없는 사건", "일부만 유효 확인", "기사 ID 확인", "근거 없는 확인", "다른 사건의 확인", "표시 금지")) {
                assertFalse(body.contains(unsupported), unsupported + " must be omitted");
            }
            assertFalse(body.contains("https://example.com/unrelated"));
        }
    }

    @Test
    void anEventWithoutMatchingWatchDoesNotInventFollowupTextOrAnEmptyHeading() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding()));
        NewsReport report = structuredReport(new ReportContent(List.of("요약"),
                List.of(event("확인 항목 없는 사건", "저장된 주요 내용", "사용하지 않을 관점", 1L)),
                List.of(), List.of()));

        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertTrue(body.contains("저장된 주요 내용"));
            assertFalse(body.contains("후속 확인"));
            assertFalse(body.contains("확인할 점"));
            assertFalse(body.contains("사용하지 않을 관점"));
        }
    }

    @Test
    void noValidEventKeepsTheExistingSavedDigestAndGenericSourceFallback() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding()));
        NewsReport report = structuredReport(new ReportContent(List.of("기존 보고서의 저장 요약"),
                List.of(event("사용할 수 없는 사건", "잘못된 사건 내용", "", 101L)), List.of(), List.of()));

        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertTrue(body.contains("기존 보고서의 저장 요약"));
            assertTrue(body.contains("참고 원문"));
            assertTrue(body.contains("https://example.com/article"));
            assertFalse(body.contains("사용할 수 없는 사건"));
            assertFalse(body.contains("잘못된 사건 내용"));
        }
    }

    @Test
    void eventAndWatchTextAreEscapedAndUnsafeSourceUrlsAreNeverLinked() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(
                finding(11L, 101L, "정상 기사", "https://example.com/safe?a=1&b=2"),
                finding(22L, 202L, "안전하지 않은 링크 기사", "javascript:alert(1)")));
        NewsReport report = structuredReport(new ReportContent(List.of("<em>요약</em>"),
                List.of(event("<script>사건</script>", "내용 & <img src=x>", "", 11L),
                        event("다른 사건", "다른 사건 내용", "", 22L)),
                List.of(watch("<b>확인 주제</b>", "이유 & <iframe>", 11L)), List.of()));

        for (ChannelType type : ChannelType.values()) {
            String body = renderer.render(report, channel(type, 3500)).chunks().getFirst();
            assertTrue(body.contains("&lt;script&gt;사건&lt;/script&gt;"));
            assertTrue(body.contains("내용 &amp; &lt;img src=x&gt;"));
            assertTrue(body.contains("&lt;b&gt;확인 주제&lt;/b&gt;"));
            assertTrue(body.contains("이유 &amp; &lt;iframe&gt;"));
            assertTrue(body.contains("https://example.com/safe?a=1&amp;b=2"));
            assertFalse(body.contains("javascript:"));
            assertTrue(org.jsoup.Jsoup.parse(body).select("script, img, iframe").isEmpty());
        }
    }

    @Test
    void telegramUsesAtMostTheFirstThreeValidStoredEvents() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding()));
        NewsReport report = structuredReport(new ReportContent(List.of("요약"),
                List.of(event("사건 일", "첫 내용", "", 1L), event("사건 이", "둘째 내용", "", 1L),
                        event("사건 삼", "셋째 내용", "", 1L), event("사건 사", "넷째 내용", "", 1L)),
                List.of(), List.of()));

        String body = renderer.render(report, channel(ChannelType.TELEGRAM, 3500)).chunks().getFirst();

        assertTrue(body.indexOf("사건 일") < body.indexOf("사건 이"));
        assertTrue(body.indexOf("사건 이") < body.indexOf("사건 삼"));
        assertTrue(body.contains("셋째 내용"));
        assertFalse(body.contains("사건 사"));
        assertFalse(body.contains("넷째 내용"));
    }

    @Test
    void longTelegramCardsFitOneCompleteHtmlMessageAndKeepReportAndSourceLinks() throws Exception {
        List<Finding> findings = new ArrayList<>();
        List<ReportContent.ImportantEvent> events = new ArrayList<>();
        List<ReportContent.WatchItem> watches = new ArrayList<>();
        for (long id = 1; id <= 4; id++) {
            findings.add(finding(id, 100 + id, "기사 " + id, "https://example.com/source-" + id));
            events.add(event("사건 " + id + " <&".repeat(100), "주요 내용 <& 😀 ".repeat(500), "제외할 관점", id));
            watches.add(watch("확인 주제 <&".repeat(100), "확인 이유 <& 😀 ".repeat(500), id));
        }
        when(findingRepository.findForReportByRunId(42L)).thenReturn(findings);
        org.springframework.test.util.ReflectionTestUtils.setField(renderer, "publicBaseUrl", "https://news.example.com");
        NewsReport report = structuredReport(new ReportContent(List.of("긴 요약 <&".repeat(500)), events, watches, List.of()));

        var rendered = renderer.render(report, channel(ChannelType.TELEGRAM, 3500));

        assertEquals(1, rendered.chunks().size());
        assertEquals("HTML", rendered.parseMode());
        String body = rendered.chunks().getFirst();
        assertTrue(body.length() <= 3500, "message length was " + body.length());
        assertTrue(body.contains("<b>"));
        assertTrue(body.contains("보고서 전체 보기</a>"));
        assertTrue(body.contains("href=\"https://news.example.com/#/reports?reportId=17\""));
        assertTrue(body.contains("href=\"https://example.com/source-1\""));
        assertFalse(body.contains("사건 4"));
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        // A strict XML parser catches truncated tags/entities, misnesting and split surrogate pairs.
        var parsed = factory.newDocumentBuilder().parse(new InputSource(new StringReader("<message>" + body + "</message>")));
        assertEquals("message", parsed.getDocumentElement().getTagName());
    }

    @Test
    void oversizedSourceUrlIsOmittedWithoutReplacingTheEventCardOrLosingTheReportLink() {
        String oversizedUrl = "https://example.com/article?tracking=" + "x".repeat(5000);
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(
                finding(11L, 101L, "긴 링크 기사", oversizedUrl)));
        org.springframework.test.util.ReflectionTestUtils.setField(renderer, "publicBaseUrl", "https://news.example.com");
        NewsReport report = structuredReport(new ReportContent(List.of("대체해서는 안 되는 전체 요약"),
                List.of(event("보존할 사건 제목", "보존할 사건 주요 내용", "", 11L)), List.of(), List.of()));

        var rendered = renderer.render(report, channel(ChannelType.TELEGRAM, 3500));

        assertEquals(1, rendered.chunks().size());
        String body = rendered.chunks().getFirst();
        assertTrue(body.length() <= 3500);
        assertTrue(body.contains("보존할 사건 제목"));
        assertTrue(body.contains("보존할 사건 주요 내용"));
        assertFalse(body.contains("대체해서는 안 되는 전체 요약"));
        assertFalse(body.contains("기사별 대체 요약"));
        assertFalse(body.contains(oversizedUrl));
        assertTrue(body.contains("href=\"https://news.example.com/#/reports?reportId=17\""));
        assertTrue(body.contains("보고서 전체 보기</a>"));
    }

    @Test
    void tinyTelegramPlainTextFallbackPreservesSpacesBetweenTitleAndSummaryWords() {
        when(findingRepository.findForReportByRunId(42L)).thenReturn(List.of(finding()));

        var rendered = renderer.render(report(""), channel(ChannelType.TELEGRAM, 50));

        assertEquals(1, rendered.chunks().size());
        String body = rendered.chunks().getFirst();
        assertTrue(body.length() <= 50);
        assertTrue(body.contains("반도체 뉴스 보고서"));
        assertTrue(body.contains("검증된 핵심 요약"));
        assertFalse(body.contains("<b>"));
        assertFalse(body.contains("반도체뉴스보고서"));
    }

    private NewsReport structuredReport(ReportContent content) {
        NewsReport report = report("# 내부 보고서");
        org.springframework.test.util.ReflectionTestUtils.setField(report, "structuredContent", content);
        return report;
    }

    private ReportContent.ImportantEvent event(String title, String summary, String significance, Long... findingIds) {
        return new ReportContent.ImportantEvent(title, summary, significance, List.of(findingIds));
    }

    private ReportContent.WatchItem watch(String topic, String reason, Long... findingIds) {
        return new ReportContent.WatchItem(topic, reason, List.of(findingIds));
    }

    private Finding finding(long findingId, long articleId, String title, String url) {
        return Finding.builder().id(findingId)
                .article(Article.builder().id(articleId).title(title).canonicalUrl(url).body("확보한 본문")
                        .fetchStatus(com.example.be.domain.collection.entity.FetchStatus.FULLTEXT).build())
                .summary("기사별 대체 요약 " + findingId)
                .analysisSource(com.example.be.domain.analysis.entity.AnalysisSource.LLM)
                .keyPoints(List.of(new com.example.be.domain.analysis.entity.FindingKeyPoint(
                        "검증된 근거", List.of(0), "grounded")))
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT).build();
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
                        .fetchStatus(com.example.be.domain.collection.entity.FetchStatus.FULLTEXT)
                        .build())
                .summary("검증된 핵심 요약")
                .analysisSource(com.example.be.domain.analysis.entity.AnalysisSource.LLM)
                .keyPoints(List.of(new com.example.be.domain.analysis.entity.FindingKeyPoint(
                        "검증된 근거", List.of(0), "grounded")))
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT)
                .build();
    }
}
