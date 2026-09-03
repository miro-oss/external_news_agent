package com.example.be.domain.topics.entity;

import org.springframework.util.StringUtils;

public record TopicKeywordChange(
        TopicKeywordBucket bucket,
        TopicKeywordChangeAction action,
        String keyword,
        String reason
) {

    public static final int MAX_KEYWORD_LENGTH = 100;
    public static final int MAX_REASON_LENGTH = 500;

    public TopicKeywordChange {
        if (bucket == null) {
            throw new IllegalArgumentException("bucket은 필수입니다.");
        }
        if (action == null) {
            throw new IllegalArgumentException("action은 필수입니다.");
        }
        if (!StringUtils.hasText(keyword)) {
            throw new IllegalArgumentException("keyword는 필수입니다.");
        }
        if (!StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("reason은 필수입니다.");
        }
        keyword = keyword.trim();
        reason = reason.trim();
        if (keyword.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword는 " + MAX_KEYWORD_LENGTH + "자 이하여야 합니다.");
        }
        if (reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason은 " + MAX_REASON_LENGTH + "자 이하여야 합니다.");
        }
    }
}
