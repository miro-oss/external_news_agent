package com.example.be.domain.collection.entity;

/**
 * 기사 본문을 어디까지 확보했는지.
 *
 * <p>RSS의 description은 잘린 요약이라 전문은 기사 URL을 다시 방문해야 얻는다(F6). 그 결과가 이 값이다.
 * 전문을 못 가져왔다고 기사를 버리지 않는다 — 제목과 링크만으로도 목록에는 쓸모가 있다.
 */
public enum FetchStatus {

    /** 피드·검색 결과의 메타데이터만 있다. 본문 추출 전이거나 대상이 아니다. */
    METADATA_ONLY,

    /** 본문까지 확보했다. */
    FULLTEXT,

    /** 페이월 등으로 본문을 막았다. 명세의 FULLTEXT_BLOCKED 경고와 짝이다. */
    FULLTEXT_BLOCKED,

    /** robots.txt가 막아서 본문을 받지 않았다. */
    ROBOTS_DISALLOWED,

    /** 네트워크·5xx 등으로 실패했다. 재시도 대상이다. */
    FETCH_FAILED;

    /** Notion 기사 목록·상세 명세가 쓰는 값. 내부 상태보다 거칠다. */
    public static final String API_OK = "OK";
    public static final String API_BLOCKED = "BLOCKED";

    /**
     * 저장값을 명세의 응답값으로 좁힌다.
     *
     * <p>명세의 {@code fetchStatus}는 {@code OK} / {@code BLOCKED} 두 가지다. 내부에서는 왜 못 받았는지를
     * 구분해야 재시도 대상과 포기 대상이 갈리므로 다섯 가지로 저장하고, 바깥으로 나갈 때 여기서 좁힌다.
     * 매핑을 두지 않으면 후속 기사 API가 내부 값을 그대로 흘려보내 명세와 다른 enum이 나간다.
     */
    public String toApiValue() {
        return this == FULLTEXT ? API_OK : API_BLOCKED;
    }
}
