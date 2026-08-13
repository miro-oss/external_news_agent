package com.example.be.domain.collection.content;

import com.example.be.domain.collection.entity.FetchStatus;

/**
 * 기사 한 건의 본문 확보 결과.
 *
 * <p>실패해도 기사를 버리지 않는다 — 제목과 링크만으로도 목록에는 쓸모가 있다.
 * 대신 <b>왜 못 받았는지</b>를 남긴다. 페이월(차단)과 서버 오류(재시도 대상)는 다른 사건이다.
 */
public record ArticleContentResult(FetchStatus status, String body) {

    public static ArticleContentResult fullText(String body) {
        return new ArticleContentResult(FetchStatus.FULLTEXT, body);
    }

    /** 응답은 왔지만 본문이 없다. 페이월이 대표적이다. */
    public static ArticleContentResult blocked() {
        return new ArticleContentResult(FetchStatus.FULLTEXT_BLOCKED, null);
    }

    public static ArticleContentResult robotsDisallowed() {
        return new ArticleContentResult(FetchStatus.ROBOTS_DISALLOWED, null);
    }

    public static ArticleContentResult failed() {
        return new ArticleContentResult(FetchStatus.FETCH_FAILED, null);
    }

    public boolean hasBody() {
        return status == FetchStatus.FULLTEXT;
    }
}
