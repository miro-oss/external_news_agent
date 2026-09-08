package com.example.be.domain.topics.entity;

/** An approval's actual change, with the keyword revision that still owns that change. */
public record TopicKeywordAppliedChange(
        TopicKeywordBucket bucket,
        String keyword,
        String before,
        String after,
        long revision
) {
}
