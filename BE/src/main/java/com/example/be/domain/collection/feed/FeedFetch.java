package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.FetchResult;

/**
 * 피드를 읽은 결과와, 다음 조건부 GET에 쓸 검증자.
 *
 * <p>{@code notModified}는 <b>실패가 아니다.</b> 바뀐 게 없다는 뜻이라 조합은 SKIPPED가 되고
 * 경고도 남지 않는다. 이걸 실패로 적으면 정상 동작하는 소스가 매 실행 경고를 쌓는다.
 */
public record FeedFetch(FetchResult result, boolean notModified, String etag, String lastModified) {

    static FeedFetch of(FetchResult result) {
        return new FeedFetch(result, false, null, null);
    }

    static FeedFetch notModified(String etag, String lastModified) {
        return new FeedFetch(FetchResult.ok(java.util.List.of()), true, etag, lastModified);
    }
}
