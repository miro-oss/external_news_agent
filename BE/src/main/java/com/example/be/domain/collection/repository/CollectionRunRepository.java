package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRun;
import com.example.be.domain.collection.entity.RunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CollectionRunRepository
        extends JpaRepository<CollectionRun, Long>, JpaSpecificationExecutor<CollectionRun> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT run FROM CollectionRun run WHERE run.id = :runId")
    Optional<CollectionRun> findByIdForUpdate(@Param("runId") Long runId);

    @EntityGraph(attributePaths = {"items", "items.topic"})
    @Query("SELECT DISTINCT run FROM CollectionRun run WHERE run.id = :runId")
    Optional<CollectionRun> findReportContextById(@Param("runId") Long runId);

    /**
     * 버튼 연타로 들어온 같은 키의 요청은 새 실행을 만들지 않고 진행 중인 실행을 돌려준다.
     * DB에도 함수 기반 유니크 인덱스가 걸려 있어, 동시 요청은 제약에서 한 번 더 걸린다.
     *
     * <p>{@code idempotencyKey}는 명세에서 선택값이다. 파생 쿼리로 두면 null 인자가
     * {@code idempotency_key IS NULL}로 해석돼, 키를 안 보낸 요청이 남이 만든 키 없는 실행을 집어
     * "이미 진행 중"이라고 답한다. 선택값의 의미를 저장 계층에서도 지키려고 조건을 직접 적는다.
     */
    @Query("""
            SELECT run
            FROM CollectionRun run
            WHERE run.idempotencyKey IS NOT NULL
              AND run.idempotencyKey = :idempotencyKey
              AND run.status IN :statuses
            ORDER BY run.id ASC
            """)
    List<CollectionRun> findInProgressByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey,
                                                       @Param("statuses") Collection<RunStatus> statuses);

    /**
     * 키가 없으면 조회 자체를 하지 않는다. 키 없는 요청은 언제나 새 실행이다.
     */
    default Optional<CollectionRun> findInProgressByOptionalIdempotencyKey(String idempotencyKey,
                                                                           Collection<RunStatus> statuses) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }

        return findInProgressByIdempotencyKey(idempotencyKey, statuses).stream().findFirst();
    }

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
     * reaper가 잡은 기동 cutoff보다 전에 시작했고, 아직 진행 중으로 남아 있는 실행.
     *
     * <p>엔티티가 아니라 id만 가져온다. 닫는 작업은 실행마다 짧은 트랜잭션을 따로 열어야 하고
     * (하나가 터져도 나머지는 닫혀야 한다), 그 안에서 어차피 다시 로드한다.
     */
    @Query("""
            SELECT run.id
            FROM CollectionRun run
            WHERE run.status IN :statuses
              AND run.startedAt < :startedBefore
            ORDER BY run.id ASC
            """)
    List<Long> findIdsByStatusInAndStartedAtBefore(@Param("statuses") Collection<RunStatus> statuses,
                                                   @Param("startedBefore") LocalDateTime startedBefore);

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
