package com.example.be.domain.collection.entity;

import com.example.be.domain.topics.entity.Topic;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** 실행을 접수한 시점의 수집 조건. 이후 주제 편집으로 과거 보고서가 바뀌지 않는다. */
@Schema(name = "ReportCollectionTopicSnapshot", description = "수집 실행 접수 시 확정한 주제 조건. 현재 주제 설정을 재조회한 값이 아님")
public record CollectionTopicSnapshot(
        @Schema(description = "접수 당시 주제 ID", example = "29") Long topicId,
        @Schema(description = "접수 당시 주제명. 보고서 제목의 주제 식별에 사용", example = "HBM 시장") String topicName,
        @Schema(description = "접수 당시 검색어. 별도 검색어가 없었던 주제는 null 가능", nullable = true, example = "HBM 반도체") String queryText,
        @Schema(description = "접수 당시 필수 키워드. 없으면 []") List<String> requiredKeywords,
        @Schema(description = "접수 당시 선택 키워드. 없으면 []") List<String> optionalKeywords,
        @Schema(description = "접수 당시 제외 키워드. 원문에서 수집 키워드로 강조하지 않음. 없으면 []") List<String> excludedKeywords,
        @Schema(description = "접수 당시 주제의 회당 수집 기사 상한", example = "100") int batchSize,
        @Schema(description = "접수 당시 수집 주기(분)", example = "1440") int intervalMinutes) {
    public CollectionTopicSnapshot {
        requiredKeywords = requiredKeywords == null ? List.of() : List.copyOf(requiredKeywords);
        optionalKeywords = optionalKeywords == null ? List.of() : List.copyOf(optionalKeywords);
        excludedKeywords = excludedKeywords == null ? List.of() : List.copyOf(excludedKeywords);
    }

    public static CollectionTopicSnapshot capture(Topic topic) {
        return new CollectionTopicSnapshot(topic.getId(), topic.getName(), topic.getQueryText(),
                topic.getRequiredKeywords(), topic.getOptionalKeywords(), topic.getExcludedKeywords(),
                topic.getBatchSize(), topic.getIntervalMinutes());
    }
}
