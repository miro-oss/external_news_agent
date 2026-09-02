package com.example.be.domain.analysis.agent.investigation;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentExploreResponse;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.service.command.ArticleContentEnricher;
import com.example.be.domain.collection.service.command.CollectionExecutor;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.repository.IssueArticleRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private IssueInvestigationContextService contextService;

    private IssueInvestigationActionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new IssueInvestigationActionExecutor(
                sourceRepository, issueArticleRepository, collectionExecutor, resultWriter,
                contentEnricher, issueClusteringService, contextService, new AgentProperties());
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

    private InvestigationContext context() {
        return new InvestigationContext(
                88L, 7L, "HBM 투자", "투자 검토", "DISPUTED",
                new BigDecimal("90"), new BigDecimal("70"),
                List.of("SK하이닉스"), List.of(), 2, 5,
                List.of(101L), List.of(), List.of(), Map.of(),
                false, "상충 보도 상태");
    }
}
