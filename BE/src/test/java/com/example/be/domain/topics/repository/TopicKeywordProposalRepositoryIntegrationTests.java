package com.example.be.domain.topics.repository;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import com.example.be.domain.collection.entity.TriggerType;
import com.example.be.domain.collection.repository.CollectionRunRepository;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("local")
@Transactional
@EnabledIfSystemProperty(named = "news.integration.db", matches = "true")
class TopicKeywordProposalRepositoryIntegrationTests {

    @Autowired private TopicKeywordProposalRepository proposals;
    @Autowired private TopicRepository topics;
    @Autowired private CollectionRunRepository runs;
    @Autowired private EntityManager entityManager;

    @ParameterizedTest
    @EnumSource(TopicKeywordProposalStatus.class)
    void everyStatusExcludesInactiveTopicsBeforePagingAndCounting(TopicKeywordProposalStatus status) {
        Topic active = topic("제안 활성 주제", true);
        Topic inactive = topic("제안 비활성 주제", false);
        for (TopicKeywordProposalStatus reviewStatus : TopicKeywordProposalStatus.values()) {
            proposal(active, reviewStatus);
            proposal(active, reviewStatus);
            proposal(inactive, reviewStatus);
        }
        flushAndClear();

        var first = proposals.findActiveTopicPageByStatus(status, PageRequest.of(0, 1));
        var last = proposals.findActiveTopicPageByStatus(status, PageRequest.of(1, 1));

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.getTotalElements()).isEqualTo(2);
        assertThat(last.hasNext()).isFalse();
        assertThat(first.getContent()).allSatisfy(proposal -> {
            assertThat(proposal.getTopic().getId()).isEqualTo(active.getId());
            assertThat(proposal.getStatus()).isEqualTo(status);
        });
        assertThat(last.getContent()).allSatisfy(proposal -> assertThat(proposal.getTopic().isActive()).isTrue());
        assertThat(first.getContent().getFirst().getId()).isGreaterThan(last.getContent().getFirst().getId());
    }

    @Test
    void allReviewStatesStillUseOnlyActiveTopicsForContentsAndTotals() {
        Topic active = topic("전체 상태 활성 주제", true);
        Topic inactive = topic("전체 상태 비활성 주제", false);
        for (TopicKeywordProposalStatus status : TopicKeywordProposalStatus.values()) {
            proposal(active, status);
            proposal(inactive, status);
        }
        flushAndClear();

        var first = proposals.findActiveTopicPageByStatus(null, PageRequest.of(0, 2));
        var last = proposals.findActiveTopicPageByStatus(null, PageRequest.of(1, 2));

        assertThat(first.getTotalElements()).isEqualTo(3);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(2).allSatisfy(proposal -> assertThat(proposal.getTopic().isActive()).isTrue());
        assertThat(last.getContent()).hasSize(1).allSatisfy(proposal -> assertThat(proposal.getTopic().isActive()).isTrue());
        assertThat(last.getTotalElements()).isEqualTo(3);
        assertThat(last.hasNext()).isFalse();
    }

    @Test
    void reactivationRestoresSavedProposalsAndCountsWithoutChangingReviewState() {
        Topic inactive = topic("재개할 제안 주제", false);
        TopicKeywordProposal proposal = proposal(inactive, TopicKeywordProposalStatus.PENDING);
        flushAndClear();
        assertThat(proposals.findActiveTopicPageByStatus(null, PageRequest.of(0, 1)).getTotalElements()).isZero();

        topics.findById(inactive.getId()).orElseThrow().changeActive(true);
        flushAndClear();
        var restored = proposals.findActiveTopicPageByStatus(TopicKeywordProposalStatus.PENDING, PageRequest.of(0, 1));
        assertThat(restored.getTotalElements()).isEqualTo(1);
        assertThat(restored.getContent()).extracting(TopicKeywordProposal::getId).containsExactly(proposal.getId());

        topics.findById(inactive.getId()).orElseThrow().changeActive(false);
        flushAndClear();
        assertThat(proposals.findActiveTopicPageByStatus(null, PageRequest.of(0, 1)).getTotalElements()).isZero();
        assertThat(proposals.findById(proposal.getId()).orElseThrow().getStatus()).isEqualTo(TopicKeywordProposalStatus.PENDING);
    }

    private Topic topic(String name, boolean active) {
        return topics.save(Topic.builder().name(name).active(active).requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of()).excludedKeywords(List.of())
                .batchSize(100).intervalMinutes(1440).build());
    }

    private TopicKeywordProposal proposal(Topic topic, TopicKeywordProposalStatus status) {
        CollectionRun run = runs.save(CollectionRun.builder().status(RunStatus.SUCCESS)
                .triggerType(TriggerType.SCHEDULED).build());
        return proposals.save(TopicKeywordProposal.builder().topic(topic).collectionRunId(run.getId())
                .idempotencyKey("proposal-visibility:" + run.getId()).summary("저장된 키워드 제안")
                .changes(List.of()).status(status).createdAt(LocalDateTime.of(2026, 9, 8, 16, 0)).build());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
