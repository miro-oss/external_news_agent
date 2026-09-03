package com.example.be.domain.topics.service.strategy;

import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyRequest;
import com.example.be.domain.analysis.agent.dto.AgentKeywordStrategyResponse;
import com.example.be.domain.analysis.agent.quota.AgentQuotaService;
import com.example.be.domain.analysis.agent.quota.QuotaReservation;
import com.example.be.domain.analysis.agent.service.AgentRunRecorder;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.domain.topics.repository.TopicRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TopicKeywordStrategyFinalizer {

    private final TopicRepository topicRepository;
    private final TopicKeywordProposalRepository proposalRepository;
    private final AgentRunRecorder runRecorder;
    private final AgentQuotaService quotaService;

    @Transactional
    public void completeSuccess(Long runId,
                                Long topicId,
                                AgentKeywordStrategyRequest request,
                                AgentKeywordStrategyResponse response,
                                List<TopicKeywordChange> changes,
                                LocalDateTime startedAt,
                                QuotaReservation reservation) {
        if (!changes.isEmpty()) {
            Topic topic = topicRepository.findById(topicId).orElseThrow();
            proposalRepository.save(TopicKeywordProposal.builder()
                    .topic(topic)
                    .collectionRunId(runId)
                    .idempotencyKey(request.idempotencyKey())
                    .summary(response.summary())
                    .changes(changes)
                    .baselineRequiredKeywords(request.topic().requiredKeywords())
                    .baselineOptionalKeywords(request.topic().optionalKeywords())
                    .baselineExcludedKeywords(request.topic().excludedKeywords())
                    .status(TopicKeywordProposalStatus.PENDING)
                    .createdAt(LocalDateTime.now(ApiTimeZone.ZONE))
                    .build());
        }
        runRecorder.recordKeywordStrategySuccess(runId, topicId, request, response, startedAt);
        quotaService.completeSuccess(reservation, response.meta().credits());
    }
}
