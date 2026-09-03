package com.example.be.domain.topics.service.command;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordBucket;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordChangeAction;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicKeywordProposalCommandServiceImplTest {

    private final TopicKeywordProposalRepository proposalRepository =
            mock(TopicKeywordProposalRepository.class);
    private final TopicKeywordProposalCommandServiceImpl service =
            new TopicKeywordProposalCommandServiceImpl(proposalRepository);

    @Test
    void approveAppliesProposedChanges() {
        Topic topic = topic();
        TopicKeywordProposal proposal = proposal(topic);
        when(proposalRepository.findWithTopicById(1L)).thenReturn(Optional.of(proposal));

        var response = service.approve(1L);

        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스", "HBM4");
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.APPROVED);
        assertThat(proposal.getReviewedAt()).isNotNull();
        assertThat(response.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void rejectKeepsCurrentKeywords() {
        Topic topic = topic();
        TopicKeywordProposal proposal = proposal(topic);
        when(proposalRepository.findWithTopicById(1L)).thenReturn(Optional.of(proposal));

        var response = service.reject(1L);

        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");
        assertThat(proposal.getStatus()).isEqualTo(TopicKeywordProposalStatus.REJECTED);
        assertThat(response.getStatus()).isEqualTo("REJECTED");
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

    private TopicKeywordProposal proposal(Topic topic) {
        return TopicKeywordProposal.builder()
                .id(1L)
                .topic(topic)
                .collectionRunId(42L)
                .idempotencyKey("run:42:topic:7:keyword-strategy")
                .summary("HBM4를 선택 키워드로 추가합니다.")
                .changes(List.of(new TopicKeywordChange(
                        TopicKeywordBucket.OPTIONAL,
                        TopicKeywordChangeAction.ADD,
                        "HBM4",
                        "이번 주기 신규 기사에서 반복 등장했습니다.")))
                .status(TopicKeywordProposalStatus.PENDING)
                .createdAt(LocalDateTime.of(2026, 9, 3, 10, 15))
                .build();
    }
}
