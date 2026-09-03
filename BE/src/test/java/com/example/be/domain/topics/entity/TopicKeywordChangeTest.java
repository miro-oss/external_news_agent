package com.example.be.domain.topics.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TopicKeywordChangeTest {

    @Test
    void appliesChangesOnlyWhenExplicitlyRequested() {
        Topic topic = Topic.builder()
                .requiredKeywords(List.of("HBM"))
                .optionalKeywords(List.of("SK하이닉스"))
                .excludedKeywords(List.of("광고"))
                .build();
        List<TopicKeywordChange> proposal = List.of(
                change(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.ADD, "HBM4"),
                change(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.REMOVE, "SK하이닉스"),
                change(TopicKeywordBucket.EXCLUDED, TopicKeywordChangeAction.ADD, "채용"));

        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");

        topic.applyKeywordChanges(proposal);

        assertThat(topic.getRequiredKeywords()).containsExactly("HBM");
        assertThat(topic.getOptionalKeywords()).containsExactly("HBM4");
        assertThat(topic.getExcludedKeywords()).containsExactly("광고", "채용");
    }

    @Test
    void treatsLegacyNullKeywordListsAsEmpty() {
        Topic topic = Topic.builder().build();

        topic.applyKeywordChanges(List.of(
                change(TopicKeywordBucket.REQUIRED, TopicKeywordChangeAction.ADD, "HBM")));

        assertThat(topic.getRequiredKeywords()).containsExactly("HBM");
        assertThat(topic.getOptionalKeywords()).isEmpty();
        assertThat(topic.getExcludedKeywords()).isEmpty();
    }

    @Test
    void appliesAddAndRemoveCaseInsensitively() {
        Topic topic = Topic.builder()
                .optionalKeywords(List.of("HBM", " hbm ", "SK하이닉스"))
                .build();

        topic.applyKeywordChanges(List.of(
                change(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.REMOVE, "hbm"),
                change(TopicKeywordBucket.OPTIONAL, TopicKeywordChangeAction.ADD, "sk하이닉스")));

        assertThat(topic.getOptionalKeywords()).containsExactly("SK하이닉스");
    }

    private TopicKeywordChange change(TopicKeywordBucket bucket,
                                      TopicKeywordChangeAction action,
                                      String keyword) {
        return new TopicKeywordChange(bucket, action, keyword, "검토 근거");
    }
}
