package com.example.be.domain.collection.service.schedule;

import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.collection.service.command.CollectionRunCreator;
import com.example.be.domain.settings.service.LlmPlanService;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollectionSchedulerTest {

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private CollectionRunCreator runCreator;

    @Mock
    private LlmPlanService planService;

    @Mock
    private AgentQuotaService quotaService;

    @InjectMocks
    private CollectionScheduler scheduler;

    @Test
    void startDueCollectionsContinuesAfterOneTopicFails() {
        when(topicRepository.findDueCollectionTopicIds(any(LocalDateTime.class))).thenReturn(List.of(1L, 2L));
        when(planService.resolveRunPlan(null)).thenReturn(AgentPlan.FREE);
        doThrow(new IllegalStateException("broken topic"))
                .when(runCreator).createScheduled(eq(1L), eq(AgentPlan.FREE), any(LocalDateTime.class));

        scheduler.startDueCollections();

        verify(runCreator).createScheduled(eq(2L), eq(AgentPlan.FREE), any(LocalDateTime.class));
        verify(quotaService, times(2)).assertRunCanStart(AgentPlan.FREE);
    }

    @Test
    void startDueCollectionsSkipsPlanAndQuotaChecksWhenNoTopicIsDue() {
        when(topicRepository.findDueCollectionTopicIds(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.startDueCollections();

        verify(planService, never()).resolveRunPlan(null);
        verify(quotaService, never()).assertRunCanStart(any());
    }
}
