package com.example.be.domain.collection.service.command;

import com.example.be.domain.analysis.service.ArticleAnalysisPipeline;
import com.example.be.domain.collection.entity.CollectionRunItem;
import com.example.be.domain.collection.repository.CollectionRunItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRunExecutionService {

    private final CollectionRunItemRepository runItemRepository;
    private final CollectionExecutor collectionExecutor;
    private final ArticleContentEnricher contentEnricher;
    private final ArticleAnalysisPipeline analysisPipeline;
    private final CollectionResultWriter resultWriter;

    public void executeRun(Long runId) {
        try {
            List<CollectionRunItem> items = runItemRepository.findExecutionItemsByRunId(runId);
            for (CollectionRunItem item : items) {
                collectionExecutor.execute(
                        item.getRun().getId(),
                        item.getId(),
                        item.getTopic(),
                        item.getSource(),
                        item.getRun().isForceRefresh());
            }
            // 메타데이터를 다 모은 뒤에 본문을 받는다. 조합마다 섞으면 같은 호스트를 번갈아 두드리게 된다.
            Set<Long> refreshedArticleIds = contentEnricher.enrich(runId);
            // 분석도 외부 어댑터 경계다. Stub 단계부터 실행 트랜잭션과 분리해 실제 LLM 교체 시에도 DB를 잡지 않는다.
            analysisPipeline.analyze(runId, refreshedArticleIds);
            resultWriter.finishRun(runId);
        } catch (RuntimeException exception) {
            log.error("수집 실행을 완료하지 못했다. runId={} error={}", runId, exception.getMessage(), exception);
            resultWriter.failRun(runId);
        }
    }
}
