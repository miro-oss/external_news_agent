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
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopicKeywordStrategyOrchestratorTest {

    private final AgentProperties properties = new AgentProperties();
    private final CollectionRunRepository runRepository = mock(CollectionRunRepository.class);
    private final CollectionRunItemRepository runItemRepository = mock(CollectionRunItemRepository.class);
    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final TopicKeywordProposalRepository proposalRepository = mock(TopicKeywordProposalRepository.class);
    private final TopicKeywordStrategyInputAssembler inputAssembler =
            mock(TopicKeywordStrategyInputAssembler.class);
    private final AgentClient agentClient = mock(AgentClient.class);
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final AgentRunRecorder runRecorder = mock(AgentRunRecorder.class);
    private final CollectionResultWriter resultWriter = mock(CollectionResultWriter.class);
    private final TopicKeywordStrategyOrchestrator orchestrator = new TopicKeywordStrategyOrchestrator(
            properties,
            runRepository,
            runItemRepository,
            topicRepository,
            proposalRepository,
            inputAssembler,
            agentClient,
            quotaService,
            runRecorder,
            resultWriter);

    @BeforeEach
    void enableAgent() {
        properties.setEnabled(true);
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
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));

        orchestrator.strategize(42L);

        ArgumentCaptor<TopicKeywordProposal> proposalCaptor = ArgumentCaptor.forClass(TopicKeywordProposal.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        TopicKeywordProposal proposal = proposalCaptor.getValue();
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.PENDING);
        assertThat(proposal.getChanges()).hasSize(1);
        assertThat(proposal.getChanges().getFirst().keyword()).isEqualTo("HBM4");
        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");
        verify(runRecorder).recordKeywordStrategySuccess(
                eq(42L), eq(7L), any(AgentKeywordStrategyRequest.class), eq(response), any());
        verify(quotaService).completeSuccess(reservation, BigDecimal.ZERO);
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
