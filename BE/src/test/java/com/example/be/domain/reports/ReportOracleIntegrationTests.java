package com.example.be.domain.reports;

import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.FindingKeyPoint;
import com.example.be.domain.analysis.entity.FindingSection;
import com.example.be.domain.analysis.entity.Relevance;
import com.example.be.domain.analysis.entity.SensitivityLevel;
import com.example.be.domain.analysis.entity.Sentiment;
import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.FetchStatus;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.ArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.reports.dto.res.ReportResDTO;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import com.example.be.domain.reports.service.ReportCreationService;
import com.example.be.domain.reports.service.ReportQueryService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import com.example.be.global.apiPayload.PageResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class ReportOracleIntegrationTests {

    @Autowired
    private SourceRepository sourceRepository;

    @Autowired
    private TopicRepository topicRepository;

    @Autowired
    private CollectionRunRepository runRepository;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private FindingRepository findingRepository;

    @Autowired
    private NewsReportRepository reportRepository;

    @Autowired
    private ReportCreationService creationService;

    @Autowired
    private ReportQueryService queryService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsOneClobReportLinksRunAndReadsNotionContract() {
        long reportCountBefore = reportRepository.count();
        String suffix = Long.toString(System.nanoTime());
        LocalDateTime now = LocalDateTime.now(ApiTimeZone.ZONE);
        Source source = sourceRepository.save(Source.builder()
                .sourceKind(Source.KIND_FEED)
                .name("M5 Oracle source " + suffix)
                .urlTemplate("https://example.com/m5-" + suffix + ".xml")
                .language("ko")
                .active(true)
                .build());
        Topic topic = topicRepository.save(Topic.builder()
                .name("M5 Oracle topic " + suffix)
                .batchSize(10)
                .intervalMinutes(60)
                .active(true)
                .build());
        CollectionRun run = runRepository.save(CollectionRun.builder()
                .status(RunStatus.RUNNING)
                .triggerType(TriggerType.MANUAL)
                .forceRefresh(false)
                .startedAt(now)
                .scannedCount(0)
                .newCount(0)
                .updatedCount(0)
                .skippedCount(0)
                .build());
        Article article = articleRepository.save(Article.builder()
                .topic(topic)
                .source(source)
                .urlHash(String.format("%064d", Math.abs(System.nanoTime() % 1_000_000_000L)))
                .canonicalUrl("https://example.com/articles/" + suffix)
                .title("Oracle에서 검증하는 M5 기사")
                .summary("수집 요약")
                .language("ko")
                .fetchStatus(FetchStatus.FULLTEXT)
                .firstSeenRun(run)
                .lastSeenRun(run)
                .collectedAt(now)
                .build());
        Finding finding = findingRepository.save(Finding.builder()
                .run(run)
                .article(article)
                .changeType(ChangeType.NEW)
                .summary("Oracle CLOB 보고서에 들어갈 한국어 요약이다.")
                .keyPoints(List.of(new FindingKeyPoint("핵심 근거", List.of(0), "grounded")))
                .intent("통합 검증")
                .sentiment(Sentiment.NEUTRAL)
                .sensitivity(com.example.be.domain.analysis.entity.FindingSensitivity.legacy(SensitivityLevel.HIGH))
                .relevance(Relevance.IMPORTANT)
                .category("정책")
                .analysisSource(AnalysisSource.LLM)
                .sections(List.of(new FindingSection(0, "Oracle 통합 테스트 본문")))
                .analyzedAt(now)
                .build());
        entityManager.flush();

        Long firstId = creationService.generate(run.getId());
        Long secondId = creationService.generate(run.getId());
        entityManager.flush();
        entityManager.clear();

        assertEquals(firstId, secondId);
        assertEquals(reportCountBefore + 1, reportRepository.count());
        assertEquals(firstId, runRepository.findById(run.getId()).orElseThrow().getReportId());
        NewsReport report = reportRepository.findById(firstId).orElseThrow();
        assertEquals(ReportStatus.FALLBACK, report.getReportStatus());
        assertTrue(report.isCoverageRecorded());
        assertEquals(List.of(finding.getId()), report.getReflectedFindingIds());
        assertEquals(List.of(), report.getExcludedFindingIds());

        ReportResDTO.Detail detail = queryService.getReport(firstId, true);
        assertEquals(run.getId(), detail.getRunId());
        assertTrue(detail.getMarkdownBody().contains("Oracle CLOB 보고서에 들어갈 한국어 요약"));
        assertEquals(1, detail.getSummaryStats().getFindingCount());
        assertEquals(1, detail.getSummaryStats().getBySensitivityLevel().get("high"));
        assertNotNull(detail.getFindings());
        assertEquals(article.getId(), detail.getFindings().getFirst().getArticleId());

        PageResponse<ReportResDTO.Summary> reports = queryService.getReports(null, null, 0, 100);
        ReportResDTO.Summary summary = reports.getContent().stream()
                .filter(candidate -> firstId.equals(candidate.getId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1, summary.getFindingCount());
        assertEquals(1, summary.getHighSensitivityCount());
        assertEquals("NOT_SENT", summary.getDeliveryStatus());

        ReportResDTO.Detail withoutFindings = queryService.getReport(firstId, false);
        assertNull(withoutFindings.getFindings());
        assertEquals(1, withoutFindings.getSummaryStats().getFindingCount());
        assertEquals(1, withoutFindings.getSummaryStats().getBySensitivityLevel().get("high"));
    }
}
