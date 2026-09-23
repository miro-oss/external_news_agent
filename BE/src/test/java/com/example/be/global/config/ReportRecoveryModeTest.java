package com.example.be.global.config;

import com.example.be.domain.analysis.repository.FindingRepository;
import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.repository.CollectionRunArticleRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.collection.service.command.CollectionRunReaper;
import com.example.be.domain.reports.service.ReportCreationService;
import com.example.be.domain.reports.service.ReportRecoveryRunner;
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
            .withUserConfiguration(SchedulingConfig.class, ReportRecoveryRunner.class, CollectionRunReaper.class)
            .withBean(ReportCreationService.class, () -> mock(ReportCreationService.class))
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
                    assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
                    assertTrue(context.getBeansOfType(TaskScheduler.class).isEmpty());
                    assertTrue(context.getBeansOfType(CollectionRunReaper.class).isEmpty());
                });
    }

    @Test
    void ordinaryStartupKeepsSchedulingAndReapingWithoutRecoveryRunner() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(ReportRecoveryRunner.class).isEmpty());
            assertEquals(1, context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).size());
            assertEquals(4, context.getBeansOfType(TaskScheduler.class).size());
            assertEquals(1, context.getBeansOfType(CollectionRunReaper.class).size());
        });
    }
}
