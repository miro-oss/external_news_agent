package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.analysis.agent.investigation.IssueInvestigationOrchestrator;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.reports.service.ReportCreationService;
import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

class CollectionRunExecutionServiceTest {

    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);
    private final CollectionExecutor collectionExecutor = mock(CollectionExecutor.class);
    private final CollectionCandidatePrioritizer candidatePrioritizer = mock(CollectionCandidatePrioritizer.class);
    private final ArticleContentEnricher contentEnricher = mock(ArticleContentEnricher.class);
    private final IssueClusteringService issueClusteringService = mock(IssueClusteringService.class);
    private final ArticleAnalysisPipeline analysisPipeline = mock(ArticleAnalysisPipeline.class);
    private final IssueInvestigationOrchestrator investigationOrchestrator =
            mock(IssueInvestigationOrchestrator.class);
    private final ReportCreationService reportCreationService = mock(ReportCreationService.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final CollectionRunExecutionService service = new CollectionRunExecutionService(
            runItemRepository, collectionExecutor, candidatePrioritizer, contentEnricher, issueClusteringService,
            analysisPipeline, investigationOrchestrator, reportCreationService, resultWriter);

    @Test
    void createsReportAfterAnalysisBeforeClosingRun() {
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of());
        when(contentEnricher.enrich(42L)).thenReturn(Set.of());

        service.executeRun(42L);

        InOrder order = inOrder(
                issueClusteringService, analysisPipeline, investigationOrchestrator,
                reportCreationService, resultWriter);
        order.verify(issueClusteringService).cluster(42L);
        order.verify(analysisPipeline).analyze(42L, Set.of());
        order.verify(investigationOrchestrator).investigate(42L);
        order.verify(reportCreationService).generate(42L);
        order.verify(resultWriter).finishRun(42L);
        verify(resultWriter, never()).failRun(42L);
    }

    @Test
    void recordsWarningAndFinishesRunWhenReportGenerationFails() {
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of());
        when(contentEnricher.enrich(42L)).thenReturn(Set.of());
        when(reportCreationService.generate(42L)).thenThrow(new IllegalStateException("report failure"));

        service.executeRun(42L);

        InOrder order = inOrder(reportCreationService, resultWriter);
        order.verify(reportCreationService).generate(42L);
        order.verify(resultWriter).addReportGenerationFailedWarning(42L, "report failure");
        order.verify(resultWriter).finishRun(42L);
        verify(resultWriter, never()).failRun(42L);
    }

    @Test
    void fallsBackToArticleAnalysisWhenIssueClusteringFails() {
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of());
        when(contentEnricher.enrich(42L)).thenReturn(Set.of(10L));
        doThrow(new IllegalStateException("cluster failure"))
                .when(issueClusteringService).cluster(42L);

        service.executeRun(42L);

        verify(resultWriter).addIssueClusteringFailedWarning(42L, "cluster failure");
        verify(analysisPipeline).analyzeWithoutClustering(42L, Set.of(10L));
        verify(reportCreationService).generate(42L);
        verify(resultWriter).finishRun(42L);
        verify(resultWriter, never()).failRun(42L);
    }

    @Test
    void collectsAndPersistsOneTopicBeforeStartingTheNextTopic() {
        CollectionRun run = CollectionRun.builder().forceRefresh(false).build();
        Topic firstTopic = Topic.builder().id(7L).build();
        Topic secondTopic = Topic.builder().id(8L).build();
        Source firstSource = Source.builder().id(11L).build();
        Source secondSource = Source.builder().id(12L).build();
        Source thirdSource = Source.builder().id(13L).build();
        CollectionRunItem first = item(1L, run, firstTopic, firstSource);
        CollectionRunItem second = item(2L, run, firstTopic, secondSource);
        CollectionRunItem third = item(3L, run, secondTopic, thirdSource);
        CollectionBatch firstBatch = CollectionBatch.failure(1L, firstTopic, firstSource, "first");
        CollectionBatch secondBatch = CollectionBatch.failure(2L, firstTopic, secondSource, "second");
        CollectionBatch thirdBatch = CollectionBatch.failure(3L, secondTopic, thirdSource, "third");
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(first, second, third));
        when(collectionExecutor.collect(1L, firstTopic, firstSource, false)).thenReturn(firstBatch);
        when(collectionExecutor.collect(2L, firstTopic, secondSource, false)).thenReturn(secondBatch);
        when(collectionExecutor.collect(3L, secondTopic, thirdSource, false)).thenReturn(thirdBatch);
        when(contentEnricher.enrich(42L)).thenReturn(Set.of());

        service.executeRun(42L);

        InOrder order = inOrder(collectionExecutor, candidatePrioritizer, resultWriter);
        order.verify(collectionExecutor).collect(1L, firstTopic, firstSource, false);
        order.verify(collectionExecutor).collect(2L, firstTopic, secondSource, false);
        order.verify(candidatePrioritizer).prioritize(List.of(firstBatch, secondBatch));
        order.verify(resultWriter).writeFailure(42L, 1L, 11L, "first");
        order.verify(resultWriter).writeFailure(42L, 2L, 12L, "second");
        order.verify(collectionExecutor).collect(3L, secondTopic, thirdSource, false);
        order.verify(candidatePrioritizer).prioritize(List.of(thirdBatch));
        order.verify(resultWriter).writeFailure(42L, 3L, 13L, "third");
    }

    private CollectionRunItem item(Long id, CollectionRun run, Topic topic, Source source) {
        return CollectionRunItem.builder()
                .id(id)
                .run(run)
                .topic(topic)
                .source(source)
                .build();
    }
}
