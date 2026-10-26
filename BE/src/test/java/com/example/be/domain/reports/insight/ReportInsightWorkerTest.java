package com.example.be.domain.reports.insight;

import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.reports.comparison.ReportCompleted;
import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportStatus;
import com.example.be.domain.reports.repository.NewsReportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReportInsightWorkerTest {
    final ReportInsightJobRepository jobs = mock(ReportInsightJobRepository.class);
    final ReportInsightJobPersistence persistence = mock(ReportInsightJobPersistence.class);
    final ReportInsightService insights = mock(ReportInsightService.class);
    final AgentProperties properties = new AgentProperties();
    final ReportInsightWorker worker = new ReportInsightWorker(jobs, persistence, insights, properties);

    @Test void completionOnlyRegistersJobsAndRegistrationFailureDoesNotUndoReport() {
        worker.completed(new ReportCompleted(17L));
        verify(persistence).enqueue(17L);
        verifyNoInteractions(insights);
        doThrow(new IllegalStateException("DB unavailable")).when(persistence).enqueue(18L);
        assertDoesNotThrow(() -> worker.completed(new ReportCompleted(18L)));
        verifyNoInteractions(insights);
    }

    @Test void recoversMissingJobsAndAttemptsEveryAudienceDespiteAnEarlierFailure() {
        var queue = Arrays.stream(Audience.values()).map(audience -> new ReportInsightJobRepository.Job(17L, audience)).toList();
        when(jobs.missingJobs()).thenReturn(List.of(17L));
        when(jobs.pending()).thenReturn(queue);
        when(persistence.claim(any())).thenReturn(true);
        doThrow(new IllegalStateException("provider failure")).when(insights).createAutomatic(17L, Audience.CHIP_MAKER);
        worker.poll();
        verify(jobs).expireRunning(any(), any());
        verify(persistence).enqueue(17L);
        for (var job : queue) {
            verify(insights).createAutomatic(17L, job.audience());
            verify(persistence).finish(job, job.audience() != Audience.CHIP_MAKER);
        }
    }

    @Test void losingDuplicateClaimsAndInterruptedAttemptsNeverCallProviderAgain() {
        var job = new ReportInsightJobRepository.Job(17L, Audience.IT_INFRA);
        when(persistence.claim(job)).thenReturn(true, false);
        doThrow(new IllegalStateException("finish failure")).when(persistence).finish(job, true);
        worker.process(job);
        worker.process(job);
        verify(insights, times(1)).createAutomatic(17L, Audience.IT_INFRA);
    }

    @Test void providerCallRunsAfterClaimTransactionHasCommitted() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        var transactions = new DataSourceTransactionManager(source);
        var reports = mock(NewsReportRepository.class);
        var job = new ReportInsightJobRepository.Job(17L, Audience.IT_INFRA);
        when(jobs.claim(eq(job), any())).thenAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            return true;
        });
        when(reports.findById(17L)).thenReturn(Optional.of(NewsReport.builder().id(17L)
                .reportStatus(ReportStatus.GENERATED).build()));
        var advice = new TransactionInterceptor();
        advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource(false));
        var proxy = new ProxyFactory(new ReportInsightJobPersistence(reports, jobs));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(advice);
        var transactional = (ReportInsightJobPersistence) proxy.getProxy();
        when(insights.createAutomatic(17L, Audience.IT_INFRA)).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            verify(connection).commit();
            return null;
        });
        new ReportInsightWorker(jobs, transactional, insights, properties).process(job);
        verify(insights).createAutomatic(17L, Audience.IT_INFRA);
        verify(connection, times(2)).commit();
        verify(connection, never()).rollback();
    }
}
