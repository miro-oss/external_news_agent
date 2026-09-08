package com.example.be.domain.topics.service.query;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.domain.topics.entity.TopicKeywordProposalStatus;
import com.example.be.domain.topics.repository.TopicKeywordProposalRepository;
import com.example.be.global.apiPayload.exception.GeneralException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TopicKeywordProposalQueryServiceImplTest {

    private final TopicKeywordProposalRepository repository = mock(TopicKeywordProposalRepository.class);
    private final TopicKeywordProposalQueryServiceImpl service = new TopicKeywordProposalQueryServiceImpl(repository);

    @ParameterizedTest
    @EnumSource(TopicKeywordProposalStatus.class)
    void preservesFilteredTotalAndPageMetadataForEveryReviewStatus(TopicKeywordProposalStatus status) {
        PageRequest request = PageRequest.of(1, 2);
        when(repository.findActiveTopicPageByStatus(status, request))
                .thenReturn(new PageImpl<>(List.of(proposal(3L, status), proposal(4L, status)), request, 5));

        var result = service.getKeywordProposals(" " + status.name().toLowerCase(Locale.ROOT) + " ", 1, 2);

        assertThat(result.getContent()).extracting("id").containsExactly(3L, 4L);
        assertThat(result.getContent()).extracting("topicName").containsOnly("활성 주제");
        assertThat(result.getPage()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(5);
        assertThat(result.getTotalPages()).isEqualTo(3);
        assertThat(result.isHasNext()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void omittedStatusUsesActiveTopicQueryAcrossAllReviewStates(String status) {
        when(repository.findActiveTopicPageByStatus(null, PageRequest.of(0, 20)))
                .thenReturn(Page.empty(PageRequest.of(0, 20)));

        var result = service.getKeywordProposals(status, 0, 20);

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        assertThat(result.getTotalPages()).isZero();
        assertThat(result.isHasNext()).isFalse();
    }

    @Test
    void rejectsInvalidStatusAndPagingBeforeQuerying() {
        assertThatThrownBy(() -> service.getKeywordProposals("UNKNOWN", 0, 20))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> service.getKeywordProposals("PENDING", -1, 20))
                .isInstanceOf(GeneralException.class);
        assertThatThrownBy(() -> service.getKeywordProposals("PENDING", 0, 101))
                .isInstanceOf(GeneralException.class);
        verifyNoInteractions(repository);
    }

    private TopicKeywordProposal proposal(Long id, TopicKeywordProposalStatus status) {
        return TopicKeywordProposal.builder().id(id)
                .topic(Topic.builder().id(7L).name("활성 주제").active(true).build())
                .collectionRunId(42L).summary("선택 키워드를 제안합니다.")
                .changes(List.of()).status(status).createdAt(LocalDateTime.of(2026, 9, 8, 16, 0))
                .build();
    }
}
