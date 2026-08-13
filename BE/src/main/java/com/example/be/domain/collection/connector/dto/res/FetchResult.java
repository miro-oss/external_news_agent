package com.example.be.domain.collection.connector.dto.res;

import com.example.be.domain.collection.entity.CollectionRunWarning;

import java.util.List;

/**
 * 소스 하나를 읽은 결과. FEED와 SEARCH가 같은 타입을 쓴다.
 *
 * <p>빈 목록만 돌려주면 호출부가 <b>기사가 정말 0건인 소스</b>와 <b>읽기 실패</b>를 구분할 수 없다.
 * 앞엣것은 SUCCESS이고 뒤엣것은 조합 실패 + 경고여야 하는데, 둘 다 SUCCESS 0건으로 기록되면
 * 화면에서 "왜 기사가 없지?"를 설명할 수 없다.
 *
 * <p>실패해도 <b>예외는 던지지 않는다.</b> 소스 하나가 죽었다고 수집 실행 전체가 멈추면 안 된다.
 */
public record FetchResult(boolean success,
                          List<CollectedArticle> articles,
                          String failureCode,
                          String failureMessage) {

    public static FetchResult ok(List<CollectedArticle> articles) {
        return new FetchResult(true, articles, null, null);
    }

    /** 응답을 받았지만 쓸 수 없었다. 4xx나 파싱 실패처럼 다시 불러도 같은 결과인 경우다. */
    public static FetchResult unreadable(String message) {
        return new FetchResult(false, List.of(), CollectionRunWarning.CODE_FEED_UNREADABLE, message);
    }

    /** 429·5xx. 잠시 뒤에는 될 수 있어 F6의 재시도 대상이다. */
    public static FetchResult rateLimited(String message) {
        return new FetchResult(false, List.of(), CollectionRunWarning.CODE_RATE_LIMITED, message);
    }

    /** 검색 API 키가 없어 호출조차 하지 않았다. 설정 문제이므로 재시도 대상이 아니다. */
    public static FetchResult providerKeyMissing(String message) {
        return new FetchResult(false, List.of(), CollectionRunWarning.CODE_PROVIDER_KEY_MISSING, message);
    }

    public static FetchResult searchFailed(String message) {
        return new FetchResult(false, List.of(), CollectionRunWarning.CODE_SEARCH_FAILED, message);
    }
}
