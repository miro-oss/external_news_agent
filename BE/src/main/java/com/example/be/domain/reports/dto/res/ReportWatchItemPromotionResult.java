package com.example.be.domain.reports.dto.res;

import io.swagger.v3.oas.annotations.media.Schema;

public record ReportWatchItemPromotionResult(@Schema(description = "다시 조회할 보고서 ID", example = "17") long reportId) { }
