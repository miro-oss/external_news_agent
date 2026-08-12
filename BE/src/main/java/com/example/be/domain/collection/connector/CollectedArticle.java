package com.example.be.domain.collection.connector;

import java.time.OffsetDateTime;

/**
 * provider가 무엇이든 같은 모양으로 돌려주는 수집 결과 1건. M3가 이걸 news_articles로 저장한다.
 *
 * <p>{@code canonicalUrl}은 언론사 원문 URL이다. 중복 제거(url_hash)와 본문 추출(F6)이 이 값을 기준으로 돌기 때문에
 * 검색 서비스가 돌려주는 미러/리다이렉트 URL을 넣으면 안 된다.
 *
 * <p>{@code publishedAt}은 발행일을 신뢰할 수 없을 때 null이다. 파싱에 실패했다고 현재 시각을 채우면
 * "최근 기사" 필터가 조용히 망가진다.
 */
public record CollectedArticle(String title,
                               String canonicalUrl,
                               String summary,
                               OffsetDateTime publishedAt,
                               String sourceName,
                               String language) {
}
