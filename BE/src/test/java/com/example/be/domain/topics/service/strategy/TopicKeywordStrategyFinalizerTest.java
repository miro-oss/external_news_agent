package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.entity.AgentPlan;
import com.example.be.domain.analysis.agent.entity.AgentTask;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordChangeAction;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicKeywordStrategyFinalizerTest {

    private final TopicRepository topicRepository = mock(TopicRepository.class);
    private final TopicKeywordProposalRepository proposalRepository =
            mock(TopicKeywordProposalRepository.class);
    private final AgentRunRecorder runRecorder = mock(AgentRunRecorder.class);
    private final AgentQuotaService quotaService = mock(AgentQuotaService.class);
    private final TopicKeywordStrategyFinalizer finalizer = new TopicKeywordStrategyFinalizer(
            topicRepository, proposalRepository, runRecorder, quotaService);

    @Test
    void storesInputBaselineBeforeRecordingAndSettlingSuccess() {
        Topic topic = topic();
        AgentKeywordStrategyRequest request = request();
        AgentKeywordStrategyResponse response = response();
        QuotaReservation reservation = reservation();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 3, 10, 0);
        List<TopicKeywordChange> changes = changes();
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));

        finalizer.completeSuccess(42L, 7L, request, response, changes, startedAt, reservation);

        ArgumentCaptor<TopicKeywordProposal> captor = ArgumentCaptor.forClass(TopicKeywordProposal.class);
        verify(proposalRepository).save(captor.capture());
        assertThat(captor.getValue().getBaselineRequiredKeywords()).containsExactly("HBM");
        assertThat(captor.getValue().getBaselineOptionalKeywords()).containsExactly("SK하이닉스");
        assertThat(captor.getValue().getBaselineExcludedKeywords()).containsExactly("광고");
        InOrder order = inOrder(proposalRepository, runRecorder, quotaService);
        order.verify(proposalRepository).save(captor.getValue());
        order.verify(runRecorder).recordKeywordStrategySuccess(42L, 7L, request, response, startedAt);
        order.verify(quotaService).completeSuccess(reservation, BigDecimal.ZERO);
    }

    @Test
    void propagatesQuotaSettlementFailureFromTransactionalFinalization() {
        Topic topic = topic();
        AgentKeywordStrategyRequest request = request();
        AgentKeywordStrategyResponse response = response();
        QuotaReservation reservation = reservation();
        LocalDateTime startedAt = LocalDateTime.of(2026, 9, 3, 10, 0);
        when(topicRepository.findById(7L)).thenReturn(Optional.of(topic));
        doThrow(new IllegalStateException("정산 실패"))
                .when(quotaService).completeSuccess(reservation, BigDecimal.ZERO);

        assertThatThrownBy(() -> finalizer.completeSuccess(
                42L, 7L, request, response, changes(), startedAt, reservation))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("정산 실패");

        verify(proposalRepository).save(org.mockito.ArgumentMatchers.any(TopicKeywordProposal.class));
        verify(runRecorder).recordKeywordStrategySuccess(42L, 7L, request, response, startedAt);
    }

    private Topic topic() {
        return Topic.builder()
                .id(7L)
                .name("HBM")
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .build();
    }

    private AgentKeywordStrategyRequest request() {
        return new AgentKeywordStrategyRequest(
                "run:42:topic:7:keyword-strategy",
                AgentPlan.FREE,
                new AgentKeywordStrategyRequest.Target("TOPIC", 7L),
                new AgentKeywordStrategyRequest.Topic(
                        "HBM", "HBM 반도체", List.of("HBM"), List.of("SK하이닉스"), List.of("광고")),
                new AgentKeywordStrategyRequest.Run(42L, "SCHEDULED", 20, 8, 2),
                List.of(),
                List.of());
    }

    private AgentKeywordStrategyResponse response() {
        return new AgentKeywordStrategyResponse(
                "HBM4를 선택 키워드로 추가합니다.",
                List.of(new AgentKeywordStrategyResponse.Proposal(
                        "OPTIONAL", "ADD", "HBM4", "신규 기사에서 반복 등장했습니다.")),
                new AgentKeywordStrategyResponse.Meta(
                        "mock", "mock", "keyword-strategy.ko.v1",
                        0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, true, false));
    }

    private List<TopicKeywordChange> changes() {
        return List.of(new TopicKeywordChange(
                TopicKeywordBucket.OPTIONAL,
                TopicKeywordChangeAction.ADD,
                "HBM4",
                "신규 기사에서 반복 등장했습니다."));
    }

    private QuotaReservation reservation() {
        return new QuotaReservation(
                1L,
                42L,
                "run:42:topic:7:keyword-strategy",
                AgentTask.KEYWORD_STRATEGY,
                AgentPlan.FREE,
                BigDecimal.ONE);
    }
}
