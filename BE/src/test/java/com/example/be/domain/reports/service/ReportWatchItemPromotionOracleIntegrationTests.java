package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.relevance.TopicRelevancePolicy;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.reports.dto.req.ReportWatchItemPromotionRequest;
import com.example.be.domain.reports.entity.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Exercises the real Oracle CLOB conversion, transaction boundaries and report row lock. */
@SpringBootTest(properties = {"news.agent.enabled=false", "news.notifications.automation-enabled=false", "news.feedback.enabled=false"})
@ActiveProfiles("local")
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportWatchItemPromotionOracleIntegrationTests {
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired ReportWatchItemPromotionService service;
    @MockitoBean FindingRepository findings;
    @MockitoBean TopicRelevancePolicy relevance;
    private final List<Long> reportIds = new ArrayList<>();
    private final ReportContent.WatchItem first = new ReportContent.WatchItem("첫 관찰 항목", "공식 발표 후 확인합니다.", List.of(501L));
    private final ReportContent.WatchItem second = new ReportContent.WatchItem("둘째 관찰 항목", "계약 이행을 확인합니다.", List.of(502L));
    private final ReportContent content = new ReportContent(List.of("원래 요약"), List.of(), List.of(first, second), List.of());

    @BeforeEach void sources() {
        when(findings.findForReportByIdIn(any())).thenReturn(List.of(finding(501), finding(502)));
        when(relevance.filterFindings(any())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach void cleanup() {
        reportIds.forEach(id -> jdbc.update("DELETE FROM news_reports WHERE id=?", id));
    }

    @Test void oldNullableRowsAndNewSelectionsRoundTripWithoutChangingGeneratedContent() {
        long id = createReport();
        jdbc.update("UPDATE news_reports SET promoted_watch_item_keys=NULL WHERE id=?", id);
        assertTrue(load(id).getPromotedWatchItemKeys().isEmpty());
        var original = jdbc.queryForObject("SELECT structured_content FROM news_reports WHERE id=?", String.class, id);

        service.promote(id, request(first));

        var saved = load(id);
        assertEquals(List.of(ReportWatchItemKey.of(first)), saved.getPromotedWatchItemKeys());
        assertEquals(content, saved.getStructuredContent());
        assertEquals(original, jdbc.queryForObject("SELECT structured_content FROM news_reports WHERE id=?", String.class, id));
        assertEquals("원본 본문", saved.getMarkdownBody());
        var reading = ReportReadingContent.from(saved, new ReportFindings.Visible(List.of(finding(501), finding(502)), false));
        assertEquals(List.of(ReportWatchItemPromotions.event(first)), reading.structuredContent().importantEvents());
        assertEquals(List.of(second), reading.structuredContent().watchItems());
    }

    @Test void concurrentDifferentSelectionsAreBothDurableAndSameSelectionRetriesStayUnique() throws Exception {
        long id = createReport();
        promoteTogether(id, first, second);
        assertEquals(Set.of(ReportWatchItemKey.of(first), ReportWatchItemKey.of(second)), Set.copyOf(load(id).getPromotedWatchItemKeys()));
        assertEquals(2, load(id).getPromotedWatchItemKeys().size());
        var order = load(id).getPromotedWatchItemKeys();
        promoteTogether(id, first, first);
        assertEquals(order, load(id).getPromotedWatchItemKeys());

        long fresh = createReport();
        promoteTogether(fresh, first, first);
        assertEquals(List.of(ReportWatchItemKey.of(first)), load(fresh).getPromotedWatchItemKeys());
    }

    private void promoteTogether(long id, ReportContent.WatchItem a, ReportContent.WatchItem b) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var firstTask = pool.submit(() -> { start.await(); return service.promote(id, request(a)); });
            var secondTask = pool.submit(() -> { start.await(); return service.promote(id, request(b)); });
            start.countDown();
            assertEquals(id, firstTask.get(10, TimeUnit.SECONDS).reportId());
            assertEquals(id, secondTask.get(10, TimeUnit.SECONDS).reportId());
        }
    }

    private long createReport() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var report = NewsReport.builder().reportScope(ReportScope.DAILY).reportDate(LocalDate.of(2191, 1, 1).plusDays(reportIds.size()))
                    .reportStatus(ReportStatus.GENERATED).title("관찰 항목 테스트 보고서").markdownBody("원본 본문")
                    .structuredContent(content).modelName("fixture").generatedAt(LocalDateTime.of(2026, 11, 9, 12, 0))
                    .reflectedFindingIds(List.of(501L, 502L)).build();
            em.persist(report);
            em.flush();
            reportIds.add(report.getId());
            return report.getId();
        });
    }

    private NewsReport load(long id) {
        return new TransactionTemplate(transactions).execute(tx -> em.find(NewsReport.class, id));
    }

    private static Finding finding(long id) {
        return Finding.builder().id(id).article(Article.builder().id(id + 100).title("기사 " + id)
                .body("확보한 본문 " + id).fetchStatus(FetchStatus.FULLTEXT).build()).summary("분석 " + id).build();
    }

    private static ReportWatchItemPromotionRequest request(ReportContent.WatchItem item) {
        return new ReportWatchItemPromotionRequest(item.topic(), item.reason(), item.sourceFindingIds());
    }
}
