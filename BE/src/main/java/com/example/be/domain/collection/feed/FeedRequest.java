package com.example.be.domain.collection.feed;

import com.example.be.domain.sources.entity.Source;

import java.time.Duration;

/**
 * 피드 한 번 읽기에 필요한 것. 소스에 저장해 둔 조건부 GET 검증자와 robots가 말한 크롤 간격을 함께 넘긴다.
 */
public record FeedRequest(String feedUrl, String language, String etag, String lastModified, Duration crawlDelay) {

    public static FeedRequest of(Source source, Duration crawlDelay, boolean forceRefresh) {
        // forceRefresh면 검증자를 비워 보내 전체를 다시 받는다. 명세의 forceRefresh가 이 뜻이다.
        return new FeedRequest(
                source.getUrlTemplate(),
                source.getLanguage(),
                forceRefresh ? null : source.getEtag(),
                forceRefresh ? null : source.getLastModified(),
                crawlDelay);
    }
}
