package com.example.be.domain.collection.service.command;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;

/** 외부 수집이 끝났지만 아직 저장 대상을 고르지 않은 조합 결과다. */
record CollectionBatch(
        Long itemId,
        Topic topic,
        Source source,
        CollectionOutcome outcome,
        String failureMessage
) {

    static CollectionBatch success(Long itemId, Topic topic, Source source, CollectionOutcome outcome) {
        return new CollectionBatch(itemId, topic, source, outcome, null);
    }

    static CollectionBatch failure(Long itemId, Topic topic, Source source, String failureMessage) {
        return new CollectionBatch(itemId, topic, source, null, failureMessage);
    }

    boolean failed() {
        return outcome == null;
    }
}
