package com.example.be.domain.collection.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

public class CollectionRunReqDTO {

    private CollectionRunReqDTO() {
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "CollectionRunCreateRequest", description = "수동 수집 실행 요청")
    public static class Create {

        @Schema(description = "대상 주제 ID 목록. 생략 시 활성 주제 전체", example = "[1, 2]")
        private List<Long> topicIds;

        @Schema(description = "중복 실행 방지 키. 최대 100자", example = "2026-08-10-manual-001")
        private String idempotencyKey;

        @Schema(description = "true면 Conditional GET(ETag/304)을 무시하고 전체 재수집. 기본값 false",
                example = "false")
        private Boolean forceRefresh;
    }
}
