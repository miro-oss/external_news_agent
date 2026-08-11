package com.example.be.domain.sources.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 소스별 수집 정책. news_sources.crawl_policy에 JSON으로 저장된다(IS JSON 제약).
 * 항목이 늘어날 수 있으므로 모르는 필드는 무시하고 읽는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(name = "CrawlPolicy", description = "소스별 수집 정책")
public record CrawlPolicy(

        @Schema(description = "robots.txt 준수 모드", example = "respect", allowableValues = {"respect", "ignore"})
        String robotsMode,

        @Schema(description = "1회 실행에서 수집할 최대 기사 수", example = "30")
        Integer maxArticlesPerRun,

        @Schema(description = "본문 전문 저장 허용 여부. 페이월 매체는 false", example = "true")
        Boolean fullTextAllowed
) {

    public static final String ROBOTS_MODE_RESPECT = "respect";
    public static final String ROBOTS_MODE_IGNORE = "ignore";
}
