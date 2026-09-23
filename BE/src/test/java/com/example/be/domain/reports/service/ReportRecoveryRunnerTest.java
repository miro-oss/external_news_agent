package com.example.be.domain.reports.service;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportRecoveryRunnerTest {

    @Test
    void regeneratesEachExplicitRunOnceInInputOrder() {
        ReportCreationService creation = mock(ReportCreationService.class);
        ArticleAnalysisPipeline analysis = mock(ArticleAnalysisPipeline.class);
        CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        when(observations.findArticleIdsByRunId(5821L)).thenReturn(List.of(11L));
        when(observations.findArticleIdsByRunId(5822L)).thenReturn(List.of(22L));
        when(findings.findForReportByRunId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        when(creation.recover(5821L)).thenReturn(301L);
        when(creation.recover(5822L)).thenReturn(302L);
        ReportRecoveryRunner runner = new ReportRecoveryRunner(creation, analysis, observations, findings);
        runner.configuredRunIds = "5821, 5822, 5821";

        runner.run(null);

        verify(analysis).recover(5821L, java.util.Set.of(11L));
        verify(analysis).recover(5822L, java.util.Set.of(22L));
        verify(creation).recover(5821L);
        verify(creation).recover(5822L);
    }

    @Test
    void rejectsInvalidRunIdsBeforeRecovery() {
        assertThrows(IllegalArgumentException.class,
                () -> ReportRecoveryRunner.parseRunIds("5821, nope"));
        assertEquals(List.of(5821L, 5822L), ReportRecoveryRunner.parseRunIds("5821,5822,5821"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", ",", "5821,", ",5821", "5821,,5822", "5821, ", "0", "-1", "false", "5821,nope"})
    void rejectsInvalidConfigurationBeforeReadingOrWritingRuns(String value) {
        ReportCreationService creation = mock(ReportCreationService.class);
        ArticleAnalysisPipeline analysis = mock(ArticleAnalysisPipeline.class);
        CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        ReportRecoveryRunner runner = new ReportRecoveryRunner(creation, analysis, observations, findings);
        runner.configuredRunIds = value;

        assertThrows(IllegalArgumentException.class, () -> runner.run(null));

        verifyNoInteractions(creation, analysis, observations, findings);
    }

    @Test
    void failsWhenRunHasNoNewArticleObservation() {
        ReportCreationService creation = mock(ReportCreationService.class);
        ArticleAnalysisPipeline analysis = mock(ArticleAnalysisPipeline.class);
        CollectionRunArticleRepository observations = mock(CollectionRunArticleRepository.class);
        FindingRepository findings = mock(FindingRepository.class);
        when(observations.findArticleIdsByRunId(5821L)).thenReturn(List.of());
        when(findings.findForReportByRunId(5821L)).thenReturn(List.of());
        when(creation.recover(5821L)).thenReturn(null);
        ReportRecoveryRunner runner = new ReportRecoveryRunner(creation, analysis, observations, findings);
        runner.configuredRunIds = "5821";

        assertThrows(IllegalStateException.class, () -> runner.run(null));
    }
}
