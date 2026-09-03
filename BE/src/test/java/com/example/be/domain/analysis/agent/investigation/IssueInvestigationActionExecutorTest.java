package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.connector.dto.res.FetchResult;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.robots.RobotsDecision;
import com.example.be.domain.collection.service.command.ArticleContentEnricher;
import com.example.be.domain.collection.service.command.CollectionExecutor;
import com.example.be.domain.collection.service.command.CollectionOutcome;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.sources.repository.SourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueInvestigationActionExecutorTest {

    @Mock
    private SourceRepository sourceRepository;
    @Mock
    private IssueArticleRepository issueArticleRepository;
    @Mock
    private CollectionExecutor collectionExecutor;
    @Mock
    private CollectionResultWriter resultWriter;
    @Mock
    private ArticleContentEnricher contentEnricher;
    @Mock
    private IssueClusteringService issueClusteringService;
    @Mock
    private ArticleAnalysisPipeline analysisPipeline;
    @Mock
    private IssueInvestigationContextService contextService;

    private IssueInvestigationActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new IssueInvestigationActionExecutor(
                sourceRepository, issueArticleRepository, collectionExecutor, resultWriter,
                contentEnricher, issueClusteringService, analysisPipeline,
                contextService, new AgentProperties());
    }

    @Test
    void compareHistoryReportsMatchesWithoutAddingCurrentIssueEvidence() {
        NewsIssue historicalIssue = NewsIssue.builder()
                .title("SK하이닉스 HBM 투자")
                .summary("과거 투자 발표")
                .entities(List.of("SK하이닉스"))
                .build();
        when(issueArticleRepository.findRecentRepresentativesByTopicIdExcludingIssueId(
                eq(7L), eq(88L), any(OffsetDateTime.class)))
                .thenReturn(List.of(IssueArticle.builder().issue(historicalIssue).build()));
        AgentExploreResponse.Proposal proposal = new AgentExploreResponse.Proposal(
                "COMPARE_HISTORY", null, null, null,
                List.of("sk 하이닉스"), 30, "과거 흐름 비교");

        InvestigationActionResult result = executor.execute(42L, context(), proposal);

        assertEquals(0, result.addedArticleCount());
        assertEquals(0, result.addedEvidenceCount());
        assertEquals("최근 30일의 관련 이슈 1건 비교", result.summary());
    }

    @Test
    void readFullTextReportsSupportedEvidenceDeltaInsteadOfBodySentenceDelta() {
        InvestigationContext before = context(2, 5, List.of(101L), Map.of());
        InvestigationContext clustered = context(2, 12, List.of(101L), Map.of());
        InvestigationContext after = context(3, 12, List.of(101L), Map.of());
        when(contentEnricher.enrichArticle(42L, 101L)).thenReturn(Set.of(101L));
        when(contextService.current(42L, 88L)).thenReturn(clustered, after);
        AgentExploreResponse.Proposal proposal = new AgentExploreResponse.Proposal(
                "READ_FULLTEXT", null, null, 101L, List.of(), null, "전문 확인");

        InvestigationActionResult result = executor.execute(42L, before, proposal);

        assertEquals(0, result.addedArticleCount());
        assertEquals(1, result.addedEvidenceCount());
        verify(contentEnricher).enrichArticle(42L, 101L);
        verify(issueClusteringService).cluster(42L);
        verify(analysisPipeline).analyzeInvestigation(42L, Set.of(101L));
    }

    @Test
    void searchReportsSupportedEvidenceDeltaInsteadOfBodySentenceDelta() {
        Source source = Source.builder()
                .id(11L)
                .sourceKind(Source.KIND_SEARCH)
                .name("네이버")
                .urlTemplate("NAVER")
                .language("ko")
                .active(true)
                .build();
        InvestigationContext before = context(
                2, 5, List.of(101L), Map.of("NAVER", 11L));
        InvestigationContext after = context(
                3, 25, List.of(101L, 102L, 103L), Map.of("NAVER", 11L));
        InvestigationContext clustered = context(
                2, 25, List.of(101L, 102L, 103L), Map.of("NAVER", 11L));
        CollectionOutcome outcome = CollectionOutcome.of(
                FetchResult.ok(List.of()), RobotsDecision.skipped(source));
        when(sourceRepository.findActiveByTopicId(7L)).thenReturn(List.of(source));
        when(collectionExecutor.collectInvestigation("HBM 투자", 10, source)).thenReturn(outcome);
        when(resultWriter.writeInvestigation(42L, 7L, 11L, outcome))
                .thenReturn(new CollectionResultWriter.InvestigationWriteResult(2, 2));
        when(contentEnricher.enrich(42L)).thenReturn(Set.of(102L, 103L));
        when(contextService.current(42L, 88L)).thenReturn(clustered, after);
        AgentExploreResponse.Proposal proposal = new AgentExploreResponse.Proposal(
                "SEARCH_MORE", "NAVER", "HBM 투자", null, List.of(), null, "추가 검색");

        InvestigationActionResult result = executor.execute(42L, before, proposal);

        assertEquals(2, result.addedArticleCount());
        assertEquals(1, result.addedEvidenceCount());
        verify(contentEnricher).enrich(42L);
        verify(issueClusteringService).cluster(42L);
        verify(analysisPipeline).analyzeInvestigation(42L, Set.of(102L, 103L));
    }

    private InvestigationContext context() {
        return context(2, 5, List.of(101L), Map.of());
    }

    private InvestigationContext context(int evidenceSentenceCount,
                                         int availableSentenceCount,
                                         List<Long> articleIds,
                                         Map<String, Long> sourceIdsByKey) {
        return new InvestigationContext(
                88L, 7L, "HBM 투자", "투자 검토", "DISPUTED",
                new BigDecimal("90"), new BigDecimal("70"),
                List.of("SK하이닉스"), List.of(), evidenceSentenceCount,
                availableSentenceCount, articleIds, List.of(), List.of(), sourceIdsByKey,
                false, "상충 보도 상태");
    }
}
