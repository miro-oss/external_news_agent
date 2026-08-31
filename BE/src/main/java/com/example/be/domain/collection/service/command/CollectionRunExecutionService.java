package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.cluster.IssueClusteringService;
import com.example.be.domain.collection.connector.dto.res.CollectedArticle;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import com.example.be.domain.reports.service.ReportCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRunExecutionService {

    private final CollectionRunItemRepository runItemRepository;
    private final CollectionExecutor collectionExecutor;
    private final CollectionCandidatePrioritizer candidatePrioritizer;
    private final ArticleContentEnricher contentEnricher;
    private final IssueClusteringService issueClusteringService;
    private final ArticleAnalysisPipeline analysisPipeline;
    private final ReportCreationService reportCreationService;
    private final CollectionResultWriter resultWriter;

    public void executeRun(Long runId) {
        try {
            List<CollectionRunItem> items = runItemRepository.findExecutionItemsByRunId(runId);
            Map<Long, List<CollectionRunItem>> itemsByTopic = new LinkedHashMap<>();
            items.forEach(item -> itemsByTopic
                    .computeIfAbsent(item.getTopic().getId(), ignored -> new ArrayList<>())
                    .add(item));
            for (List<CollectionRunItem> topicItems : itemsByTopic.values()) {
                collectAndWriteTopic(runId, topicItems);
            }
            // 메타데이터를 다 모은 뒤에 본문을 받는다. 조합마다 섞으면 같은 호스트를 번갈아 두드리게 된다.
            Set<Long> refreshedArticleIds = contentEnricher.enrich(runId);
            // FULLTEXT가 확정된 뒤에만 SimHash를 계산하고, 대표를 정한 다음 분석한다.
            boolean clustered = true;
            try {
                issueClusteringService.cluster(runId);
            } catch (RuntimeException exception) {
                clustered = false;
                log.error("이슈 클러스터링에 실패해 기사 단위 분석으로 전환한다. runId={} error={}",
                        runId, exception.getMessage(), exception);
                resultWriter.addIssueClusteringFailedWarning(runId, exception.getMessage());
            }
            // 분석도 외부 어댑터 경계다. Stub 단계부터 실행 트랜잭션과 분리해 실제 LLM 교체 시에도 DB를 잡지 않는다.
            // 기사 루프 안의 실패는 파이프라인이 직접 경고로 남긴다. 여기서 잡는 것은 대상 선별처럼
            // 루프 바깥에서 터지는 예외다. 이걸 흘려보내면 수집이 성공했는데도 RUN_REJECTED로 닫힌다.
            try {
                if (clustered) {
                    analysisPipeline.analyze(runId, refreshedArticleIds);
                } else {
                    analysisPipeline.analyzeWithoutClustering(runId, refreshedArticleIds);
                }
            } catch (RuntimeException exception) {
                log.error("기사 분석 단계에 실패해 보고서 생성으로 넘어간다. runId={} error={}",
                        runId, exception.getMessage(), exception);
                resultWriter.addAnalysisFailedWarning(runId, exception.getMessage());
            }
            // M5 보고서는 findings를 모두 저장한 뒤 만든다. 생성과 reportId 연결은 별도 짧은 트랜잭션이다.
            try {
                reportCreationService.generate(runId);
            } catch (RuntimeException exception) {
                log.error("보고서를 생성하지 못했다. runId={} error={}", runId, exception.getMessage(), exception);
                resultWriter.addReportGenerationFailedWarning(runId, exception.getMessage());
            }
            resultWriter.finishRun(runId);
        } catch (RuntimeException exception) {
            log.error("수집 실행을 완료하지 못했다. runId={} error={}", runId, exception.getMessage(), exception);
            resultWriter.failRun(runId);
        }
    }

    private void collectAndWriteTopic(Long runId, List<CollectionRunItem> items) {
        List<CollectionBatch> batches = items.stream()
                .map(item -> collectionExecutor.collect(
                        item.getId(),
                        item.getTopic(),
                        item.getSource(),
                        item.getRun().isForceRefresh()))
                .toList();
        Map<Long, List<CollectedArticle>> selected = candidatePrioritizer.prioritize(batches);
        for (CollectionBatch batch : batches) {
            if (batch.failed()) {
                resultWriter.writeFailure(
                        runId, batch.itemId(), batch.source().getId(), batch.failureMessage());
                continue;
            }
            try {
                resultWriter.writeSelected(
                        runId,
                        batch.itemId(),
                        batch.topic().getId(),
                        batch.source().getId(),
                        batch.outcome(),
                        selected.getOrDefault(batch.itemId(), List.of()));
            } catch (RuntimeException exception) {
                log.warn("조합 저장에 실패했다. topicId={} sourceId={} error={}",
                        batch.topic().getId(), batch.source().getId(), exception.getMessage(), exception);
                resultWriter.writeFailure(
                        runId, batch.itemId(), batch.source().getId(), messageOf(exception));
            }
        }
    }

    private String messageOf(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
