package com.example.be.domain.collection.feed;

import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.entity.CollectionRunWarning;

import java.util.List;

/**
 * 피드 읽기 결과.
 *
 * <p>빈 목록만 돌려주면 <b>기사가 정말 0건인 피드</b>와 <b>읽기 실패</b>를 호출부가 구분할 수 없다.
 * 앞엣것은 SUCCESS이고 뒤엣것은 조합 실패 + 경고여야 하는데, 둘 다 SUCCESS 0건으로 기록돼
 * 화면에서 "왜 기사가 없지?"를 설명할 수 없게 된다.
 */
public record FeedFetchResult(boolean success,
                              List<CollectedArticle> articles,
                              String failureCode,
                              String failureMessage) {

    public static FeedFetchResult ok(List<CollectedArticle> articles) {
        return new FeedFetchResult(true, articles, null, null);
    }

    public static FeedFetchResult unreadable(String message) {
        return new FeedFetchResult(false, List.of(), CollectionRunWarning.CODE_FEED_UNREADABLE, message);
    }

    public static FeedFetchResult rateLimited(String message) {
        return new FeedFetchResult(false, List.of(), CollectionRunWarning.CODE_RATE_LIMITED, message);
    }
}
