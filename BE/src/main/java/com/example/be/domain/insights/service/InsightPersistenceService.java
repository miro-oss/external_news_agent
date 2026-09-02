package com.example.be.domain.insights.service;

import com.example.be.domain.analysis.agent.dto.AgentInsightResponse;
import com.example.be.domain.analysis.agent.entity.AgentTargetType;
import com.example.be.domain.analysis.entity.Audience;
import com.example.be.domain.insights.dto.InsightDTO;
import com.example.be.domain.insights.entity.InsightFact;
import com.example.be.domain.insights.entity.InsightImplication;
import com.example.be.domain.insights.entity.NewsInsight;
import com.example.be.domain.insights.repository.NewsInsightRepository;
import com.example.be.global.config.ApiTimeZone;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InsightPersistenceService {

    private final NewsInsightRepository repository;

    @Transactional(readOnly = true)
    public List<NewsInsight> findCached(AgentTargetType targetType,
                                       Long targetId,
                                       String inputHash,
                                       String promptVersion,
                                       Collection<Audience> audiences) {
        if (audiences.isEmpty()) {
            return List.of();
        }
        return repository.findByTargetTypeAndTargetIdAndInputHashAndPromptVersionAndAudienceIn(
                targetType, targetId, inputHash, promptVersion, audiences);
    }

    @Transactional(readOnly = true)
    public Optional<NewsInsight> findLatest(AgentTargetType targetType,
                                            Long targetId,
                                            Audience audience) {
        return repository.findFirstByTargetTypeAndTargetIdAndAudienceOrderByCreatedAtDescIdDesc(
                targetType, targetId, audience);
    }

    @Transactional
    public List<NewsInsight> saveGenerated(AgentTargetType targetType,
                                           Long targetId,
                                           String inputHash,
                                           AgentInsightResponse response,
                                           Map<Long, Long> articleIdsByFinding) {
        AgentInsightResponse.Meta meta = response.meta();
        LocalDateTime createdAt = LocalDateTime.now(ApiTimeZone.ZONE);
        List<NewsInsight> insights = response.insights().stream()
                .map(insight -> NewsInsight.builder()
                        .targetType(targetType)
                        .targetId(targetId)
                        .audience(Audience.fromApiValue(insight.audience()))
                        .headline(insight.headline())
                        .facts(insight.facts().stream()
                                .map(fact -> toFact(fact, articleIdsByFinding))
                                .toList())
                        .implications(insight.implications().stream()
                                .map(this::toImplication).toList())
                        .watchNext(List.copyOf(insight.watchNext()))
                        .confidence(insight.confidence())
                        .inputHash(inputHash)
                        .promptVersion(meta.promptVersion())
                        .llmProvider(meta.provider())
                        .llmModel(meta.model())
                        .inputTokens(meta.inputTokens())
                        .outputTokens(meta.outputTokens())
                        .costUsd(meta.costUsd())
                        .credits(meta.credits())
                        .createdAt(createdAt)
                        .build())
                .toList();
        return repository.saveAll(insights);
    }

    public InsightDTO.AudienceInsight toDto(NewsInsight insight) {
        return new InsightDTO.AudienceInsight(
                insight.getAudience(),
                insight.getHeadline(),
                List.copyOf(insight.getFacts()),
                List.copyOf(insight.getImplications()),
                List.copyOf(insight.getWatchNext()),
                insight.getConfidence(),
                insight.getLlmProvider(),
                insight.getLlmModel(),
                insight.getCreatedAt().atZone(ApiTimeZone.ZONE).toOffsetDateTime());
    }

    private InsightFact toFact(AgentInsightResponse.Fact fact,
                               Map<Long, Long> articleIdsByFinding) {
        return new InsightFact(
                fact.claimType(),
                fact.id(),
                fact.text(),
                fact.findingId(),
                articleIdsByFinding.get(fact.findingId()),
                fact.evidenceSentenceIds().stream().map(id -> id - 1).toList(),
                fact.groundedness(),
                fact.groundingReason());
    }

    private InsightImplication toImplication(AgentInsightResponse.Implication implication) {
        return new InsightImplication(
                implication.claimType(),
                implication.id(),
                implication.text(),
                implication.basisFactIds(),
                implication.assumption(),
                implication.falsifiedBy());
    }
}
