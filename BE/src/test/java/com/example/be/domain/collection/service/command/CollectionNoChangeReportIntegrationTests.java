package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.content.ArticleContentClient;
import com.example.be.domain.collection.content.ArticleContentResult;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.CollectionRunWarning;
import com.example.be.domain.collection.entity.RunItemStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.feed.FeedClient;
import com.example.be.domain.collection.feed.FeedFetch;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.robots.RobotsPolicyService;
import com.example.be.domain.notifications.service.ReportNotificationAutomationService;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.sources.entity.CrawlPolicy;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.domain.topics.service.strategy.TopicKeywordStrategyOrchestrator;
import com.example.be.global.config.ApiTimeZone;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 실제 수집·분석·보고서 저장 흐름에서 변경 없는 실행의 이력과 보고서 생략을 함께 검증한다. */
@SpringBootTest(properties = "news.agent.enabled=false")
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class CollectionNoChangeReportIntegrationTests {

    @Autowired private CollectionRunExecutionService executionService;
    @Autowired private CollectionResultWriter resultWriter;
    @Autowired private CollectionRunRepository runRepository;
    @Autowired private CollectionRunArticleRepository observationRepository;
    @Autowired private NewsReportRepository reportRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private TopicRepository topicRepository;
    @Autowired private SourceRepository sourceRepository;
    @Autowired private EntityManager entityManager;

    @MockitoBean private FeedClient feedClient;
    @MockitoBean private ArticleContentClient contentClient;
    @MockitoBean private RobotsPolicyService robotsPolicyService;
    @MockitoBean private ReportNotificationAutomationService notificationAutomation;
    @MockitoBean private TopicKeywordStrategyOrchestrator keywordStrategyOrchestrator;

    private Topic topic;
    private Source source;
    private String articleUrl;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        topic = topicRepository.save(Topic.builder().name("HBM " + suffix).queryText("HBM")
                .requiredKeywords(List.of("HBM")).optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(10).intervalMinutes(60).active(true).build());
        source = sourceRepository.save(Source.builder().sourceKind(Source.KIND_FEED)
                .name("변경 감지 테스트 소스").urlTemplate("https://example.com/feed/" + suffix)
                .language("ko").active(true)
                .crawlPolicy(new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 10, false)).build());
        articleUrl = "https://example.com/articles/" + suffix;
        when(robotsPolicyService.evaluate(any())).thenAnswer(invocation ->
                RobotsDecision.skipped(invocation.getArgument(0)));
    }

    @ParameterizedTest
    @EnumSource(TriggerType.class)
    void unchangedAndUpdatedOnlyCollectionKeepsHistoryWithoutAnotherReportOrDelivery(TriggerType triggerType) {
        givenArticle("HBM 양산 일정 발표");
        CollectionRun first = execute(triggerType);
        assertNotNull(first.getReportId());
        assertEquals(1, first.getNewCount());
        clearInvocations(notificationAutomation);

        CollectionRun unchanged = execute(triggerType);

        assertSkippedReport(unchanged);
        assertEquals(1, unchanged.getScannedCount());
        assertEquals(0, unchanged.getNewCount());
        assertEquals(0, unchanged.getUpdatedCount());
        assertEquals(1, unchanged.getSkippedCount());
        assertEquals(1, observationRepository.countByRunIdAndChangeType(
                unchanged.getId(), ChangeType.UNCHANGED));
        assertTrue(reportRepository.findById(first.getReportId()).isPresent());

        givenArticle("HBM 양산 일정 변경 발표");
        CollectionRun updated = execute(triggerType);
        assertSkippedReport(updated);
        assertEquals(0, updated.getNewCount());
        assertEquals(1, updated.getUpdatedCount());
        assertEquals(1, observationRepository.countByRunIdAndChangeType(updated.getId(), ChangeType.UPDATED));
        assertTrue(findingRepository.existsByRunId(updated.getId()));
    }

    @Test
    void notModifiedFeedFinishesSuccessfullyWithoutReport() {
        when(feedClient.fetch(any())).thenReturn(new FeedFetch(FetchResult.ok(List.of()), true, "v1", null));

        CollectionRun run = execute(TriggerType.SCHEDULED);

        assertSkippedReport(run);
        assertEquals(RunItemStatus.SKIPPED, run.getItems().getFirst().getStatus());
        assertEquals(0, run.getScannedCount());
    }

    @Test
    void emptySuccessfulFeedFinishesWithoutReport() {
        when(feedClient.fetch(any())).thenReturn(new FeedFetch(FetchResult.ok(List.of()), false, null, null));

        CollectionRun run = execute(TriggerType.SCHEDULED);

        assertSkippedReport(run);
        assertEquals(RunItemStatus.SUCCESS, run.getItems().getFirst().getStatus());
    }

    @Test
    void failedFeedRetainsFailureWithoutReport() {
        when(feedClient.fetch(any())).thenReturn(new FeedFetch(
                FetchResult.unreadable("테스트 피드 응답 실패"), false, null, null));

        CollectionRun run = execute(TriggerType.SCHEDULED);

        assertEquals(RunStatus.FAILED, run.getStatus());
        assertEquals(RunItemStatus.FAILED, run.getItems().getFirst().getStatus());
        assertTrue(run.getWarnings().stream().anyMatch(warning ->
                "테스트 피드 응답 실패".equals(warning.getMessage())));
        assertNoReport(run);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unchangedCollectionWithKeywordStrategyFailureKeepsWarningWithoutReport(boolean recordsWarningInternally) {
        when(feedClient.fetch(any())).thenReturn(new FeedFetch(FetchResult.ok(List.of()), true, "v1", null));
        if (recordsWarningInternally) {
            doAnswer(invocation -> {
                resultWriter.addAgentWarning(invocation.getArgument(0),
                        CollectionRunWarning.CODE_LLM_KEYWORD_STRATEGY_FAILED, "키워드 제안 예산 부족");
                return null;
            }).when(keywordStrategyOrchestrator).strategize(anyLong());
        } else {
            doThrow(new IllegalStateException("키워드 전략 실행 실패"))
                    .when(keywordStrategyOrchestrator).strategize(anyLong());
        }

        CollectionRun run = execute(TriggerType.SCHEDULED);

        assertEquals(RunStatus.PARTIAL, run.getStatus());
        assertNotNull(run.getFinishedAt());
        assertEquals(RunItemStatus.SKIPPED, run.getItems().getFirst().getStatus());
        assertEquals(0, run.getNewCount());
        assertEquals(0, run.getUpdatedCount());
        assertTrue(run.getWarnings().stream().anyMatch(warning ->
                CollectionRunWarning.CODE_LLM_KEYWORD_STRATEGY_FAILED.equals(warning.getCode())));
        assertNoReport(run);
    }

    @Test
    void newlyAvailableFullTextAndReanalysisDoNotCreateReportWithoutNewArticles() {
        givenArticle("HBM 양산 일정 발표");
        CollectionRun first = execute(TriggerType.SCHEDULED);
        assertNotNull(first.getReportId());
        clearInvocations(notificationAutomation);
        source = sourceRepository.findById(source.getId()).orElseThrow();
        source.update(source.getName(), source.getUrlTemplate(), source.getCountry(), source.getLanguage(),
                new CrawlPolicy(CrawlPolicy.ROBOTS_MODE_IGNORE, 10, true), source.getReliabilityScore(), true);
        when(contentClient.fetch(articleUrl, null)).thenReturn(
                ArticleContentResult.fullText("HBM 양산 일정에 대한 상세 내용을 새 본문으로 확보했다."));

        CollectionRun refreshed = execute(TriggerType.SCHEDULED);

        assertSkippedReport(refreshed);
        assertEquals(0, refreshed.getNewCount());
        assertEquals(0, refreshed.getUpdatedCount());
        assertEquals(1, observationRepository.countByRunIdAndChangeType(refreshed.getId(), ChangeType.UNCHANGED));
        assertTrue(findingRepository.existsByRunId(refreshed.getId()));
        verify(contentClient).fetch(articleUrl, null);
    }

    @Test
    void newArticleStillCreatesReportWhenKeywordStrategyFails() {
        givenArticle("HBM 양산 일정 발표");
        doThrow(new IllegalStateException("키워드 전략 실행 실패"))
                .when(keywordStrategyOrchestrator).strategize(anyLong());

        CollectionRun run = execute(TriggerType.SCHEDULED);

        assertEquals(RunStatus.PARTIAL, run.getStatus());
        assertEquals(1, run.getNewCount());
        assertTrue(run.getWarnings().stream().anyMatch(warning ->
                CollectionRunWarning.CODE_LLM_KEYWORD_STRATEGY_FAILED.equals(warning.getCode())));
        assertNotNull(run.getReportId());
        assertTrue(reportRepository.findByRunId(run.getId()).isPresent());
        verify(notificationAutomation).enqueueCompletedReport(any());
    }

    private void givenArticle(String title) {
        when(feedClient.fetch(any())).thenReturn(new FeedFetch(FetchResult.ok(List.of(new CollectedArticle(
                title, articleUrl, "HBM 양산 일정을 발표했다.", OffsetDateTime.now(ApiTimeZone.ZONE),
                "테스트 소스", "ko"))), false, null, null));
    }

    private CollectionRun execute(TriggerType triggerType) {
        CollectionRun run = CollectionRun.builder().status(RunStatus.RUNNING).triggerType(triggerType)
                .startedAt(LocalDateTime.now(ApiTimeZone.ZONE)).build();
        run.addItem(CollectionRunItem.builder().topic(topic).source(source).status(RunItemStatus.RUNNING).build());
        Long runId = runRepository.saveAndFlush(run).getId();
        executionService.executeRun(runId);
        entityManager.flush();
        entityManager.clear();
        return runRepository.findReportContextById(runId).orElseThrow();
    }

    private void assertSkippedReport(CollectionRun run) {
        assertEquals(RunStatus.SUCCESS, run.getStatus());
        assertNotNull(run.getFinishedAt());
        assertTrue(run.getWarnings().isEmpty());
        assertNoReport(run);
    }

    private void assertNoReport(CollectionRun run) {
        assertNotNull(run.getFinishedAt());
        assertNull(run.getReportId());
        assertTrue(reportRepository.findByRunId(run.getId()).isEmpty());
        verify(notificationAutomation, never()).enqueueCompletedReport(any());
    }
}
