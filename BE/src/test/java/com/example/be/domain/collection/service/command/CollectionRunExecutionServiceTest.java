package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.reports.service.ReportCreationService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CollectionRunExecutionServiceTest {

    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);
    private final CollectionExecutor collectionExecutor = mock(CollectionExecutor.class);
    private final ArticleContentEnricher contentEnricher = mock(ArticleContentEnricher.class);
    private final IssueClusteringService issueClusteringService = mock(IssueClusteringService.class);
    private final ArticleAnalysisPipeline analysisPipeline = mock(ArticleAnalysisPipeline.class);
    private final ReportCreationService reportCreationService = mock(ReportCreationService.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final CollectionRunExecutionService service = new CollectionRunExecutionService(
            runItemRepository, collectionExecutor, contentEnricher, issueClusteringService,
            analysisPipeline, reportCreationService, resultWriter);

    @Test
    void createsReportAfterAnalysisBeforeClosingRun() {
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of());
        when(contentEnricher.enrich(42L)).thenReturn(Set.of());

        service.executeRun(42L);

        InOrder order = inOrder(issueClusteringService, analysisPipeline, reportCreationService, resultWriter);
        order.verify(issueClusteringService).cluster(42L);
        order.verify(analysisPipeline).analyze(42L, Set.of());
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
}
