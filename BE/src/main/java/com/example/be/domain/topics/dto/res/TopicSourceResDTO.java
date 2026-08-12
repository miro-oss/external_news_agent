package com.example.be.domain.topics.dto.res;

import com.example.be.global.apiPayload.PageResponse;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

public class TopicSourceResDTO {

    private TopicSourceResDTO() {
    }

    /**
     * 공통 PageResponse에 combinationCount 한 칸을 더한 형태다. 조합 폭발(소스 수 × 주제 수)을
     * 화면에서 바로 보여주려고 명세가 목록 루트에 이 값을 요구한다.
     */
    @Getter
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @JsonPropertyOrder({"content", "page", "size", "totalElements", "totalPages", "hasNext", "combinationCount"})
    @Schema(name = "TopicSourceCombinationPageResponse", description = "등록된 수집 조합 목록")
    public static class CombinationPage {

        @Schema(description = "조회된 조합 목록")
        private final List<Combination> content;

        @Schema(description = "페이지 번호 (0부터 시작)", example = "0")
        private final int page;

        @Schema(description = "페이지 크기", example = "20")
        private final int size;

        @Schema(description = "전체 건수", example = "2")
        private final long totalElements;

        @Schema(description = "전체 페이지 수", example = "1")
        private final int totalPages;

        @Schema(description = "다음 페이지 존재 여부", example = "false")
        private final boolean hasNext;

        @Schema(description = "필터에 걸린 (주제 × 소스) 조합 수", example = "2")
        private final long combinationCount;

        public static CombinationPage from(PageResponse<Combination> page) {
            return new CombinationPage(
                    page.getContent(),
                    page.getPage(),
                    page.getSize(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.isHasNext(),
                    page.getTotalElements()
            );
        }
    }

    @Getter
    @Builder
    @AllArgsConstructor(access = AccessLevel.PRIVATE)
    @Schema(name = "TopicSourceCombinationResponse", description = "수집 조합 1건. 한 행 = (주제 × 소스)")
    public static class Combination {

        @Schema(description = "수집 주제 ID", example = "1")
        private final Long topicId;

        @Schema(description = "주제명", example = "HBM")
        private final String topicName;

        @Schema(description = "수집 소스 ID", example = "2")
        private final Long sourceId;

        @Schema(description = "소스명", example = "Google News RSS")
        private final String sourceName;

        @Schema(description = "소스 종류", example = "SEARCH", allowableValues = {"FEED", "SEARCH"})
        private final String sourceKind;

        @Schema(description = "SEARCH 소스에 넘길 검색어. FEED 조합이면 null", example = "HBM 반도체")
        private final String queryText;

        @Schema(description = "1회 수집 건수", example = "10")
        private final int batchSize;

        @Schema(description = "자동 수집 주기(분)", example = "60")
        private final int intervalMinutes;

        @Schema(description = "조합의 활성 여부. 주제와 소스가 모두 활성일 때만 true", example = "true")
        private final boolean active;

        @Schema(description = "주제의 마지막 수집 시각", example = "2026-08-10T08:00:00+09:00")
        private final OffsetDateTime lastCollectedAt;

        @Schema(description = "마지막 수집 건수. 수집 실행 이력이 생기는 M3까지는 null", example = "7")
        private final Integer lastCollectedCount;
    }
}
