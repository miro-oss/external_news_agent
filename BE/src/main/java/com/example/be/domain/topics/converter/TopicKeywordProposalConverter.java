package com.example.be.domain.topics.converter;

import com.example.be.domain.topics.dto.res.TopicKeywordProposalResDTO;
import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicKeywordChange;
import com.example.be.domain.topics.entity.TopicKeywordProposal;
import com.example.be.global.config.ApiTimeZone;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

public class TopicKeywordProposalConverter {

    private TopicKeywordProposalConverter() {
    }

    public static TopicKeywordProposalResDTO.Item toItem(TopicKeywordProposal proposal) {
        Topic topic = proposal.getTopic();
        return TopicKeywordProposalResDTO.Item.builder()
                .id(proposal.getId())
                .topicId(topic.getId())
                .topicName(topic.getName())
                .collectionRunId(proposal.getCollectionRunId())
                .status(proposal.getStatus().name())
                .summary(proposal.getSummary())
                .reviewedAt(toOffsetDateTime(proposal.getReviewedAt()))
                .createdAt(toOffsetDateTime(proposal.getCreatedAt()))
                .currentKeywords(TopicKeywordProposalResDTO.CurrentKeywords.builder()
                        .requiredKeywords(keywordsOrEmpty(topic.getRequiredKeywords()))
                        .optionalKeywords(keywordsOrEmpty(topic.getOptionalKeywords()))
                        .excludedKeywords(keywordsOrEmpty(topic.getExcludedKeywords()))
                        .build())
                .changes(proposal.getChanges().stream().map(TopicKeywordProposalConverter::toChange).toList())
                .build();
    }

    private static TopicKeywordProposalResDTO.Change toChange(TopicKeywordChange change) {
        return TopicKeywordProposalResDTO.Change.builder()
                .bucket(change.bucket().name())
                .action(change.action().name())
                .keyword(change.keyword())
                .reason(change.reason())
                .build();
    }

    private static OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atZone(ApiTimeZone.ZONE).toOffsetDateTime();
    }

    private static List<String> keywordsOrEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
