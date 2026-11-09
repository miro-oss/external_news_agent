package com.example.be.domain.collection.scoring;

import com.example.be.domain.topics.entity.Topic;
import com.example.be.domain.topics.entity.TopicEditorialFocus;

import java.time.OffsetDateTime;

/** The same technical-news window applies to collection, investigation and downstream selection. */
public final class ArticleRecencyPolicy {

    private ArticleRecencyPolicy() { }

    public static boolean isEligible(Topic topic, OffsetDateTime publishedAt,
                                     OffsetDateTime referenceTime, int lookbackDays) {
        // Missing publication dates stay unknown; never substitute the collection time.
        return !TopicEditorialFocus.isAiTechnology(topic) || publishedAt == null || referenceTime == null
                || !publishedAt.isBefore(referenceTime.minusDays(lookbackDays));
    }
}
