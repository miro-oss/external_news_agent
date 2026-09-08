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

        @Schema(description = "이번 수집의 보고서 전달 설정. 생략하면 기존 주제 자동 전달 정책을 유지합니다")
        private Delivery delivery;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @Schema(name = "CollectionRunDeliveryRequest", description = "수집 접수 시 확정할 보고서 전달 대상")
    public static class Delivery {
        @Schema(description = "자동 전달 여부. false이면 이번 실행을 기존 주제 정책으로도 전달하지 않습니다", requiredMode = Schema.RequiredMode.REQUIRED)
        private Boolean enabled;

        @Schema(description = "ONCE는 이번 수집만, TOPIC은 실제 실행 대상 주제의 이후 자동 전달 정책에도 적용", allowableValues = {"ONCE", "TOPIC"}, requiredMode = Schema.RequiredMode.REQUIRED)
        private String mode;

        @Schema(description = "실행별 보고서 전달. 생략 시 true", defaultValue = "true")
        private Boolean run;

        @Schema(description = "이 실행을 포함하는 일일 통합 보고서 전달. 다른 주제 내용도 포함됩니다. 생략 시 false", defaultValue = "false")
        private Boolean daily;

        @Schema(description = "수신 그룹 ID. 양수 최대 100개, 중복 제거")
        private List<Long> groupIds;

        @Schema(description = "개별 수신자 ID. 양수 최대 100개, 중복 제거")
        private List<Long> recipientIds;

        @Schema(description = "전달 채널 ID. 양수 최대 100개, 중복 제거")
        private List<Long> channelIds;
    }
}
