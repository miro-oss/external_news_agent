package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.client.AgentClient;
import com.example.be.domain.analysis.agent.config.AgentProperties;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.collection.service.command.CollectionResultWriter;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopicKeywordStrategyOrchestratorTest {

    private final AgentProperties properties = new AgentProperties();
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);
    private final TopicKeywordProposalRepository proposalRepository = mock(TopicKeywordProposalRepository.class);
    private final TopicKeywordStrategyInputAssembler inputAssembler =
            mock(TopicKeywordStrategyInputAssembler.class);
    private final TopicKeywordStrategyRequestBudgeter requestBudgeter =
            mock(TopicKeywordStrategyRequestBudgeter.class);
    private final TopicKeywordStrategyFinalizer finalizer = mock(TopicKeywordStrategyFinalizer.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final AgentRunRecorder runRecorder = mock(AgentRunRecorder.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final TopicKeywordStrategyOrchestrator orchestrator = new TopicKeywordStrategyOrchestrator(
            properties,
            runRepository,
            runItemRepository,
            proposalRepository,
            inputAssembler,
            requestBudgeter,
            finalizer,
            agentClient,
            quotaService,
            runRecorder,
            resultWriter);

    @BeforeEach
    void enableAgent() {
        properties.setEnabled(true);
        when(requestBudgeter.fit(any(AgentKeywordStrategyRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesPendingProposalWithoutChangingTopicKeywords() {
        Topic topic = topic();
        CollectionRun run = run(TriggerType.SCHEDULED);
        CollectionRunItem item = CollectionRunItem.builder()
                .id(11L)
                .run(run)
                .topic(topic)
                .scannedCount(30)
                .newCount(8)
                .updatedCount(2)
                .build();
        TopicKeywordStrategyInputAssembler.Snapshot snapshot = new TopicKeywordStrategyInputAssembler.Snapshot(
                new AgentKeywordStrategyRequest.Topic(
                        topic.getName(),
                        topic.getQueryText(),
                        topic.getRequiredKeywords(),
                        topic.getOptionalKeywords(),
                        topic.getExcludedKeywords()),
                List.of(),
                List.of());
        QuotaReservation reservation = new QuotaReservation(
                1L,
                42L,
                "run:42:topic:7:keyword-strategy",
                AgentTask.KEYWORD_STRATEGY,
                AgentPlan.FREE,
                BigDecimal.ONE);
        AgentKeywordStrategyResponse response = response();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(item));
        when(proposalRepository.existsByTopic_IdAndStatus(7L, TopicKeywordProposalStatus.PENDING))
                .thenReturn(false);
        when(inputAssembler.assemble(42L, 7L)).thenReturn(snapshot);
        when(quotaService.reserve(
                42L,
                "run:42:topic:7:keyword-strategy",
                AgentTask.KEYWORD_STRATEGY,
                AgentPlan.FREE)).thenReturn(reservation);
        when(agentClient.keywordStrategy(any(AgentKeywordStrategyRequest.class))).thenReturn(response);

        orchestrator.strategize(42L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TopicKeywordChange>> changesCaptor = ArgumentCaptor.forClass(List.class);
        verify(finalizer).completeSuccess(
                eq(42L),
                eq(7L),
                any(AgentKeywordStrategyRequest.class),
                eq(response),
                changesCaptor.capture(),
                any(),
                eq(reservation));
        assertThat(changesCaptor.getValue()).singleElement()
                .extracting(TopicKeywordChange::keyword)
                .isEqualTo("HBM4");
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");
    }

    @Test
    void skipsManualRuns() {
        when(runRepository.findById(42L)).thenReturn(Optional.of(run(TriggerType.MANUAL)));

        orchestrator.strategize(42L);

        verifyNoInteractions(runItemRepository, inputAssembler, agentClient, quotaService);
    }

    @Test
    void skipsTopicThatAlreadyHasPendingProposal() {
        Topic topic = topic();
        CollectionRun run = run(TriggerType.SCHEDULED);
        CollectionRunItem item = CollectionRunItem.builder().id(11L).run(run).topic(topic).build();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(item));
        when(proposalRepository.existsByTopic_IdAndStatus(7L, TopicKeywordProposalStatus.PENDING))
                .thenReturn(true);

        orchestrator.strategize(42L);

        verify(inputAssembler, never()).assemble(42L, 7L);
        verifyNoInteractions(agentClient, quotaService);
    }

    @Test
    void recordsFailureAndReleasesReservationWhenAtomicFinalizationFails() {
        Topic topic = topic();
        CollectionRun run = run(TriggerType.SCHEDULED);
        CollectionRunItem item = CollectionRunItem.builder().id(11L).run(run).topic(topic).build();
        TopicKeywordStrategyInputAssembler.Snapshot snapshot = new TopicKeywordStrategyInputAssembler.Snapshot(
                new AgentKeywordStrategyRequest.Topic(
                        topic.getName(),
                        topic.getQueryText(),
                        topic.getRequiredKeywords(),
                        topic.getOptionalKeywords(),
                        topic.getExcludedKeywords()),
                List.of(),
                List.of());
        QuotaReservation reservation = new QuotaReservation(
                1L,
                42L,
                "run:42:topic:7:keyword-strategy",
                AgentTask.KEYWORD_STRATEGY,
                AgentPlan.FREE,
                BigDecimal.ONE);
        AgentKeywordStrategyResponse response = response();
        when(runRepository.findById(42L)).thenReturn(Optional.of(run));
        when(runItemRepository.findExecutionItemsByRunId(42L)).thenReturn(List.of(item));
        when(proposalRepository.existsByTopic_IdAndStatus(7L, TopicKeywordProposalStatus.PENDING))
                .thenReturn(false);
        when(inputAssembler.assemble(42L, 7L)).thenReturn(snapshot);
        when(quotaService.reserve(
                42L,
                "run:42:topic:7:keyword-strategy",
                AgentTask.KEYWORD_STRATEGY,
                AgentPlan.FREE)).thenReturn(reservation);
        when(agentClient.keywordStrategy(any(AgentKeywordStrategyRequest.class))).thenReturn(response);
        doThrow(new IllegalStateException("정산 실패"))
                .when(finalizer).completeSuccess(
                        eq(42L), eq(7L), any(), eq(response), any(), any(), eq(reservation));

        orchestrator.strategize(42L);

        verify(runRecorder).recordKeywordStrategyFailure(
                eq(42L),
                eq(7L),
                any(AgentKeywordStrategyRequest.class),
                eq("SCHEMA_VIOLATION"),
                eq("정산 실패"),
                eq(null),
                eq(null),
                any());
        verify(quotaService).completeFailure(reservation, "SCHEMA_VIOLATION");
        verify(resultWriter).addAgentWarning(
                eq(42L),
                eq("LLM_KEYWORD_STRATEGY_FAILED"),
                any(String.class));
    }

    private CollectionRun run(TriggerType triggerType) {
        return CollectionRun.builder()
                .id(42L)
                .triggerType(triggerType)
                .llmPlan(AgentPlan.FREE)
                .build();
    }

    private Topic topic() {
        return Topic.builder()
                .id(7L)
                .name("HBM")
                .queryText("HBM 반도체")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .build();
    }

    private AgentKeywordStrategyResponse response() {
        return new AgentKeywordStrategyResponse(
                "HBM4를 선택 키워드로 추가합니다.",
                List.of(new AgentKeywordStrategyResponse.Proposal(
                        "OPTIONAL",
                        "ADD",
                        "HBM4",
                        "이번 주기 신규 기사에서 반복 등장했습니다.")),
                new AgentKeywordStrategyResponse.Meta(
                        "mock",
                        "mock",
                        "keyword-strategy.ko.v1",
                        0L,
                        0L,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        true,
                        false));
    }
}
