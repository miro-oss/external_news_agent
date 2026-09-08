package com.example.be.domain.reports.entity;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.CollectionTopicSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;

@Schema(name = "ReportCollectionContext", description = "보고서에 포함된 수집 실행의 저장된 조건")
public record ReportCollectionContext(
        @Schema(description = "수집 실행 ID. RUN 보고서는 해당 runId, DAILY 보고서는 sourceRunIds 중 하나", example = "148") Long runId,
        @Schema(description = "실행 접수 시 저장한 주제별 수집 조건. 소스별로 반복된 같은 주제는 중복 제거. 스냅샷 저장 이전 실행은 []")
        List<CollectionTopicSnapshot> topics) {
    public ReportCollectionContext { topics = List.copyOf(topics); }

    public static ReportCollectionContext from(CollectionRun run) {
        return new ReportCollectionContext(run.getId(), run.getItems().stream()
                .map(item -> item.getTopicSnapshot()).filter(Objects::nonNull).distinct().toList());
    }
}
