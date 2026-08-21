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

        @Schema(description = "대상 주제 ID 목록(예: [1, 2]). 생략 시 활성 주제 전체")
        private List<Long> topicIds;

        @Schema(description = "중복 실행 방지 키. 최대 100자(예: 2026-08-10-manual-001). 같은 키로 진행 중인 실행이 있으면 새로 만들지 않는다")
        private String idempotencyKey;

        @Schema(description = "true면 Conditional GET(ETag/304)을 무시하고 전체 재수집. 기본값 false",
                example = "false")
        private Boolean forceRefresh;

        @Schema(description = "이번 실행에만 적용할 LLM 플랜. FREE / PAID. 생략 시 저장된 기본 플랜")
        private String plan;
    }
}
