package com.example.be.global.config;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.analysis.service.FailedAnalysisRecoveryService;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.collection.service.command.CollectionRunReaper;
import com.example.be.domain.reports.service.ReportCreationService;
import com.example.be.domain.reports.service.ReportMaintenanceService;
import com.example.be.domain.reports.service.ReportRecoveryRunner;
import com.example.be.domain.reports.service.ReportRepairRunner;
import com.example.be.domain.reports.service.ReportSnapshotRecoveryComparison;
import com.example.be.domain.reports.service.ReportSnapshotRecoveryRunner;
import com.example.be.domain.reports.service.ReportSnapshotRecoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReportRecoveryModeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, ReportRecoveryRunner.class,
                    ReportRepairRunner.class, ReportSnapshotRecoveryRunner.class, CollectionRunReaper.class)
            .withBean(ReportCreationService.class, () -> mock(ReportCreationService.class))
            .withBean(ReportMaintenanceService.class, () -> mock(ReportMaintenanceService.class))
            .withBean(FailedAnalysisRecoveryService.class, () -> mock(FailedAnalysisRecoveryService.class))
            .withBean(ReportSnapshotRecoveryService.class, () -> mock(ReportSnapshotRecoveryService.class))
            .withBean(ReportSnapshotRecoveryComparison.class, () -> mock(ReportSnapshotRecoveryComparison.class))
            .withBean(ArticleAnalysisPipeline.class, () -> mock(ArticleAnalysisPipeline.class))
            .withBean(CollectionRunArticleRepository.class, () -> mock(CollectionRunArticleRepository.class))
            .withBean(FindingRepository.class, () -> mock(FindingRepository.class))
            .withBean(CollectionRunRepository.class, () -> mock(CollectionRunRepository.class))
            .withBean(CollectionResultWriter.class, () -> mock(CollectionResultWriter.class))
            .withPropertyValues("news.scheduling.enabled=true", "news.collection.reap-on-startup=true");

    @ParameterizedTest
    @ValueSource(strings = {"5821,5822", "", ",", "5821,", "false"})
    void anyRecoveryConfigurationDisablesSchedulingAndStartupReaping(String runIds) {
        runner.withPropertyValues(ReportRecoveryMode.RUN_IDS_PROPERTY + "=" + runIds)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(ReportRecoveryRunner.class).size());
                    assertTrue(context.getBeansOfType(ReportRepairRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReportSnapshotRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }

    @Test
    void snapshotRecoveryAlsoDisablesSchedulingReapingAndArticleReanalysis() {
        runner.withPropertyValues(ReportRecoveryMode.SNAPSHOT_REPORT_IDS_PROPERTY + "=17,18",
                        ReportRecoveryMode.RUN_IDS_PROPERTY + "=99")
                .run(context -> {
                    assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
                    assertEquals(1, context.getBeansOfType(ReportSnapshotRecoveryRunner.class).size());
                    assertTrue(context.getBeansOfType(ReportRepairRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }

    @Test
    void ordinaryStartupKeepsSchedulingAndReapingWithoutRecoveryRunner() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
            assertTrue(context.getBeansOfType(ReportRepairRunner.class).isEmpty());
            assertTrue(context.getBeansOfType(ReportSnapshotRecoveryRunner.class).isEmpty());
            assertEquals(1, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size());
            assertEquals(6, context.getBeansOfType(TaskScheduler.class).size());
            assertTrue(context.containsBean("reportInsightScheduler"));
            assertTrue(context.containsBean("dartScheduler"));
            assertEquals(1, context.getBeansOfType(CollectionRunReaper.class).size());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2310", "", "invalid"})
    void repairOnlyConfigurationIsolatesBackgroundWorkEvenWhenInvalid(String reportId) {
        runner.withPropertyValues(ReportRecoveryMode.REPAIR_REPORT_ID_PROPERTY + "=" + reportId)
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(ReportRepairRunner.class).size());
                    assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReportSnapshotRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }

    @Test
    void repairDisablesRunRecoveryWithoutSnapshotConfiguration() {
        runner.withPropertyValues(ReportRecoveryMode.REPAIR_REPORT_ID_PROPERTY + "=2310",
                        ReportRecoveryMode.RUN_IDS_PROPERTY + "=99")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(ReportRepairRunner.class).size());
                    assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReportSnapshotRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"2310", "", "invalid"})
    void mixedRepairConfigurationSelectsOnlyRepairRunnerEvenWhenInvalid(String reportId) {
        runner.withPropertyValues(ReportRecoveryMode.REPAIR_REPORT_ID_PROPERTY + "=" + reportId,
                        ReportRecoveryMode.RUN_IDS_PROPERTY + "=99",
                        ReportRecoveryMode.SNAPSHOT_REPORT_IDS_PROPERTY + "=17")
                .run(context -> {
                    assertEquals(1, context.getBeansOfType(ReportRepairRunner.class).size());
                    assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ReportSnapshotRecoveryRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }
}
