package com.example.be.domain.sources.dto.req;

import com.example.be.domain.sources.entity.CrawlPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

// 만들 때 DTO 컨테이너 패턴으로 - 중첩 정적 클래스 패턴
public class SourceReqDTO {

    private SourceReqDTO() {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "SourceCreateRequest", description = "수집 소스 등록 요청")
    public static class Create {

        @Schema(description = "소스 종류", example = "SEARCH", allowableValues = {"FEED", "SEARCH"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String sourceKind;

        @Schema(description = "소스명. 최대 200자", example = "Naver 뉴스 검색",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String name;

        @Schema(description = """
                FEED는 고정 http/https URL. SEARCH는 provider 키(NAVER, TAVILY, SERPAPI) 중 하나.
                Naver는 인증 헤더, Tavily는 POST 바디, SerpAPI는 api_key 쿼리 파라미터를 쓰므로 provider 키를 쓴다.
                최대 1000자
                """, example = "NAVER", requiredMode = Schema.RequiredMode.REQUIRED)
        private String urlTemplate;

        @Schema(description = "ISO 국가 코드 2자리", example = "KR")
        private String country;

        @Schema(description = "언어 코드. 최대 5자", example = "ko")
        private String language;

        @Schema(description = "수집 정책")
        private CrawlPolicy crawlPolicy;

        @Schema(description = "소스 신뢰도. 0.00 ~ 1.00", example = "0.9")
        private BigDecimal reliabilityScore;

        @Schema(description = "활성 여부. 기본값 true", example = "true")
        private Boolean active;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "SourceUpdateRequest",
            description = "수집 소스 수정 요청. 전달한 필드만 반영된다. robotsStatus와 robotsCheckedAt은 요청으로 바꿀 수 없다")
    public static class Update {

        @Schema(description = "소스명. 최대 200자", example = "ETNews 반도체 (개편)")
        private String name;

        @Schema(description = "URL 템플릿. 등록과 같은 규칙이 적용된다. 최대 1000자",
                example = "https://rss.etnews.com/Section902.xml")
        private String urlTemplate;

        @Schema(description = "ISO 국가 코드 2자리", example = "KR")
        private String country;

        @Schema(description = "언어 코드. 최대 5자", example = "ko")
        private String language;

        @Schema(description = "수집 정책. 통째로 교체된다")
        private CrawlPolicy crawlPolicy;

        @Schema(description = "소스 신뢰도. 0.00 ~ 1.00", example = "0.85")
        private BigDecimal reliabilityScore;

        @Schema(description = "활성 여부", example = "false")
        private Boolean active;
    }
}
