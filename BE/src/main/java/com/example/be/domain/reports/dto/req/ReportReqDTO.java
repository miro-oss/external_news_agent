package com.example.be.domain.reports.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;

public final class ReportReqDTO {
    private ReportReqDTO() { }
    @Schema(name = "TopicWeeklyReportRequest")
    public record WeeklyCreate(
            @Schema(description = "수집 주제 ID. 중지된 주제도 선택 가능", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") Long topicId,
            @Schema(description = "Asia/Seoul 기준 종료된 월~일 주차의 월요일", requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-09-21") LocalDate weekStartDate) { }
}
