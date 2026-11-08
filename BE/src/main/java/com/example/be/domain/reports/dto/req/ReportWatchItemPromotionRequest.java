package com.example.be.domain.reports.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "현재 표시된 관찰 항목 전체. 서버가 저장 문구와 모든 근거의 일치를 확인하며 임의 내용으로 교체하지 않습니다.")
public record ReportWatchItemPromotionRequest(
        @Schema(description = "관찰 항목의 topic 원문", example = "HBM4 공급 일정 확인") String topic,
        @Schema(description = "관찰 항목의 reason 원문", example = "공식 공급 일정 발표를 확인해야 합니다.") String reason,
        @Schema(description = "관찰 항목의 sourceFindingIds 전체를 저장된 순서대로 전달", example = "[501,502]")
        List<Long> sourceFindingIds) { }
