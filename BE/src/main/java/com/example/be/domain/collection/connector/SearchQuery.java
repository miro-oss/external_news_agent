package com.example.be.domain.collection.connector;

import org.springframework.util.StringUtils;

/**
 * 검색형 소스에 넘길 질의. 주제(news_topics)의 query_text / batch_size에서 만든다.
 *
 * <p>범위 검증을 여기서 하는 이유는 네이버가 400을 뱉기 전에 우리가 막기 위해서다. 상한 100은
 * 네이버 {@code display}의 최댓값이자 news_topics.batch_size CHECK 제약의 상한과 같다.
 */
public record SearchQuery(String queryText, int batchSize, String language) {

    public static final int MIN_BATCH_SIZE = 1;
    public static final int MAX_BATCH_SIZE = 100;

    public SearchQuery {
        if (!StringUtils.hasText(queryText)) {
            throw new IllegalArgumentException("queryText는 비어 있을 수 없습니다.");
        }
        if (batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "batchSize는 %d 이상 %d 이하여야 합니다. 값=%d".formatted(MIN_BATCH_SIZE, MAX_BATCH_SIZE, batchSize));
        }
    }
}
