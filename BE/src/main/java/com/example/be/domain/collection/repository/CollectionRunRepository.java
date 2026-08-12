package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CollectionRunRepository
        extends JpaRepository<CollectionRun, Long>, JpaSpecificationExecutor<CollectionRun> {

    /**
     * 버튼 연타로 들어온 같은 키의 요청은 새 실행을 만들지 않고 진행 중인 실행을 돌려준다.
     * DB에도 함수 기반 유니크 인덱스가 걸려 있어, 동시 요청은 제약에서 한 번 더 걸린다.
     */
    Optional<CollectionRun> findFirstByIdempotencyKeyAndStatusIn(String idempotencyKey,
                                                                 Collection<RunStatus> statuses);

    /**
     * 같은 주제를 이미 수집 중인 실행을 찾는다. 명세의 RUN409가 이 결과로 판정된다.
     */
    @Query("""
            SELECT DISTINCT item.run
            FROM CollectionRunItem item
            WHERE item.topic.id IN :topicIds
              AND item.run.status IN :statuses
            """)
    List<CollectionRun> findInProgressByTopicIds(@Param("topicIds") Collection<Long> topicIds,
                                                 @Param("statuses") Collection<RunStatus> statuses);

    /**
     * 목록 조회에서 실행마다 경고를 지연 로딩하지 않도록 개수만 한 번에 센다.
     */
    @Query("""
            SELECT run.id AS runId, SIZE(run.warnings) AS warningCount
            FROM CollectionRun run
            WHERE run.id IN :runIds
            """)
    List<WarningCount> countWarnings(@Param("runIds") Collection<Long> runIds);

    interface WarningCount {

        Long getRunId();

        int getWarningCount();
    }
}
