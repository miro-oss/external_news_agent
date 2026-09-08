package com.example.be.domain.reports.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 검증을 마친 보고서의 읽기 구조. 화면과 알림이 동일한 요약을 사용한다. */
@Schema(name = "ReportStructuredContent", description = "저장된 보고서 읽기 구조. 값이 있으면 각 목록은 null 대신 빈 배열을 사용. 구조화 저장 이전 보고서는 이 객체 전체가 null")
public record ReportContent(
        @Schema(description = "보고서 핵심 요약 문장 목록. 화면과 이메일·텔레그램 요약에서 재사용")
        List<String> executiveSummary,
        @Schema(description = "기사 근거를 연결한 중요 이벤트 목록")
        List<ImportantEvent> importantEvents,
        @Schema(description = "후속 관찰 항목과 관찰 이유 목록")
        List<WatchItem> watchItems,
        @Schema(description = "수집 범위·자료 제약 등 참고 문장. 근거 문장 개수나 기사 목록을 의미하지 않음")
        List<String> sourceNotes) {
    public ReportContent {
        executiveSummary = executiveSummary == null ? List.of() : List.copyOf(executiveSummary);
        importantEvents = importantEvents == null ? List.of() : List.copyOf(importantEvents);
        watchItems = watchItems == null ? List.of() : List.copyOf(watchItems);
        sourceNotes = sourceNotes == null ? List.of() : List.copyOf(sourceNotes);
    }
    @Schema(name = "ReportImportantEvent", description = "보고서 중요 이벤트")
    public record ImportantEvent(
            @Schema(description = "이벤트 제목") String title,
            @Schema(description = "이벤트의 한국어 요약") String summaryKo,
            @Schema(description = "이벤트가 중요한 이유. 별도 설명이 없으면 빈 문자열") String significance,
            @Schema(description = "이벤트를 뒷받침하는 분석 ID 목록. findings[].id를 참조하며 기사 ID나 문장 인덱스가 아님")
            List<Long> sourceFindingIds) {
        public ImportantEvent { sourceFindingIds = List.copyOf(sourceFindingIds); }
    }
    @Schema(name = "ReportWatchItem", description = "보고서 후속 관찰 항목")
    public record WatchItem(
            @Schema(description = "관찰할 주제 또는 변화") String topic,
            @Schema(description = "후속 관찰이 필요한 이유") String reason,
            @Schema(description = "관찰 항목을 뒷받침하는 분석 ID 목록. findings[].id 참조") List<Long> sourceFindingIds) {
        public WatchItem { sourceFindingIds = List.copyOf(sourceFindingIds); }
    }
}
