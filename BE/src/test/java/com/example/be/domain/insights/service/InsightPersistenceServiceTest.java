package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.collection.cluster.IssueClusteringProperties;
import com.example.be.domain.collection.entity.Article;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.NewsIssue;
import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import com.example.be.domain.issues.repository.IssueArticleRepository;
import com.example.be.domain.issues.repository.NewsIssueRepository;
import com.example.be.domain.issues.repository.NewsWatchRepository;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InsightPersistenceServiceTest {

    @Test
    void storesProductEvidenceIndexesAsZeroBased() {
        NewsInsightRepository repository = mock(NewsInsightRepository.class);
        NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
        IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
        NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
        InsightHypothesisEntityExtractor entityExtractor = mock(InsightHypothesisEntityExtractor.class);
        IssueClusteringProperties clusteringProperties = new IssueClusteringProperties();
        AgentProperties agentProperties = new AgentProperties();
        InsightPersistenceService service = new InsightPersistenceService(
                repository,
                issueRepository,
                issueArticleRepository,
                watchRepository,
                entityExtractor,
                new InsightEntityNormalizer(),
                clusteringProperties,
                agentProperties);
        NewsIssue issue = NewsIssue.builder()
                .id(88L)
                .topic(Topic.builder().id(7L).build())
                .build();
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(issueRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of(
                IssueArticle.builder().article(Article.builder().id(10L).build()).build(),
                IssueArticle.builder().article(Article.builder().id(11L).build()).build()));
        when(entityExtractor.extract(any(), any()))
                .thenReturn(List.of("삼성전자", "HBM4"));
        when(watchRepository.findByIssueIdAndWatchType(88L, WatchType.HYPOTHESIS))
                .thenReturn(Optional.empty());
        AgentInsightResponse response = new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        "CHIP_MAKER",
                        "양산 일정 변화",
                        List.of(new AgentInsightResponse.Fact(
                                "FACT", "f1", "확인된 사실", 501L,
                                List.of(1, 3), "grounded", "원문 확인")),
                        List.of(new AgentInsightResponse.Implication(
                                "IMPLICATION", "i1", "점검 필요", List.of("f1"),
                                "일정 유지", "일정 번복")),
                        List.of("후속 발표"),
                        new BigDecimal("0.8"))),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", "insight.ko.v2+perspective.ko.v1",
                        20L, 10L, new BigDecimal("0.1"), BigDecimal.ONE, false, false));

        service.saveGenerated(
                AgentTargetType.ISSUE,
                88L,
                "a".repeat(64),
                response,
                Map.of(501L, 10L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<NewsInsight>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        NewsInsight saved = captor.getValue().getFirst();
        assertEquals(Audience.CHIP_MAKER, saved.getAudience());
        assertEquals(10L, saved.getFacts().getFirst().articleId());
        assertEquals(List.of(0, 2), saved.getFacts().getFirst().evidenceSentenceIds());
        assertEquals("일정 번복", saved.getImplications().getFirst().falsifiedBy());
        assertEquals(List.of(10L, 11L), saved.getInputArticleIds());
        assertEquals(List.of("삼성전자", "HBM4"), saved.getWatchEntities());

        ArgumentCaptor<NewsWatch> watchCaptor = ArgumentCaptor.forClass(NewsWatch.class);
        verify(watchRepository).save(watchCaptor.capture());
        assertEquals(WatchType.HYPOTHESIS, watchCaptor.getValue().getWatchType());
        assertEquals(88L, watchCaptor.getValue().getIssue().getId());
        verify(issueRepository).findByIdForUpdate(88L);
    }

    @Test
    void doesNotRegisterWatchWhenHypothesisHasTooFewEntities() {
        NewsInsightRepository repository = mock(NewsInsightRepository.class);
        NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
        IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
        NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
        InsightHypothesisEntityExtractor entityExtractor = mock(InsightHypothesisEntityExtractor.class);
        InsightPersistenceService service = new InsightPersistenceService(
                repository,
                issueRepository,
                issueArticleRepository,
                watchRepository,
                entityExtractor,
                new InsightEntityNormalizer(),
                new IssueClusteringProperties(),
                new AgentProperties());
        NewsIssue issue = NewsIssue.builder().id(88L).topic(Topic.builder().id(7L).build()).build();
        when(issueRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of());
        when(entityExtractor.extract(any(), any())).thenReturn(List.of("HBM4"));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        service.saveGenerated(
                AgentTargetType.ISSUE,
                88L,
                "a".repeat(64),
                minimalResponse(),
                Map.of());

        verify(watchRepository, never()).save(any());
    }

    @Test
    void renewsExistingHypothesisWatch() {
        NewsInsightRepository repository = mock(NewsInsightRepository.class);
        NewsIssueRepository issueRepository = mock(NewsIssueRepository.class);
        IssueArticleRepository issueArticleRepository = mock(IssueArticleRepository.class);
        NewsWatchRepository watchRepository = mock(NewsWatchRepository.class);
        InsightHypothesisEntityExtractor entityExtractor = mock(InsightHypothesisEntityExtractor.class);
        InsightPersistenceService service = new InsightPersistenceService(
                repository,
                issueRepository,
                issueArticleRepository,
                watchRepository,
                entityExtractor,
                new InsightEntityNormalizer(),
                new IssueClusteringProperties(),
                new AgentProperties());
        NewsIssue issue = NewsIssue.builder().id(88L).topic(Topic.builder().id(7L).build()).build();
        NewsWatch existing = NewsWatch.builder()
                .watchType(WatchType.HYPOTHESIS)
                .issue(issue)
                .expiresAt(LocalDateTime.now().minusDays(1))
                .active(false)
                .build();
        when(issueRepository.findByIdForUpdate(88L)).thenReturn(Optional.of(issue));
        when(issueArticleRepository.findByIssueIdOrderByJoinedAtAsc(88L)).thenReturn(List.of());
        when(entityExtractor.extract(any(), any())).thenReturn(List.of("삼성전자", "HBM4"));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(watchRepository.findByIssueIdAndWatchType(88L, WatchType.HYPOTHESIS))
                .thenReturn(Optional.of(existing));

        service.saveGenerated(
                AgentTargetType.ISSUE,
                88L,
                "a".repeat(64),
                minimalResponse(),
                Map.of());

        assertTrue(existing.isActive());
        assertTrue(existing.getExpiresAt().isAfter(LocalDateTime.now().plusDays(29)));
        verify(watchRepository, never()).save(any());
    }

    private AgentInsightResponse minimalResponse() {
        return new AgentInsightResponse(
                List.of(new AgentInsightResponse.Insight(
                        "CHIP_MAKER",
                        "양산 일정 변화",
                        List.of(),
                        List.of(),
                        List.of("후속 발표"),
                        new BigDecimal("0.8"))),
                new AgentInsightResponse.Meta(
                        "gemini", "gemini-test", "insight.ko.v2+perspective.ko.v1",
                        20L, 10L, new BigDecimal("0.1"), BigDecimal.ONE, false, false));
    }
}
