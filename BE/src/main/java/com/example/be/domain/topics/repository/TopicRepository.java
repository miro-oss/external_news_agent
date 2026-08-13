package com.example.be.domain.topics.repository;

import com.example.be.domain.sources.entity.Source;
import com.example.be.domain.topics.entity.Topic;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface TopicRepository extends JpaRepository<Topic, Long>, JpaSpecificationExecutor<Topic> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    /**
     * 목록 조회에서 주제마다 소스 컬렉션을 지연 로딩하지 않도록 연결 수만 한 번에 센다.
     */
    @Query("SELECT t.id AS topicId, SIZE(t.sources) AS linkedSourceCount FROM Topic t WHERE t.id IN :topicIds")
    List<LinkedSourceCount> countLinkedSources(@Param("topicIds") Collection<Long> topicIds);

    /**
     * 설정 화면의 "등록된 수집 주제" 테이블은 한 행이 (주제 × 소스) 조합 1건이라 조인 결과를 펼쳐서 내려준다.
     * active 필터는 주제와 소스가 모두 활성인 조합을 뜻하므로, false를 주면 둘 중 하나라도 꺼진 조합이 나온다.
     */
    @Query(value = """
            SELECT t.id AS topicId, t.name AS topicName,
                   s.id AS sourceId, s.name AS sourceName, s.sourceKind AS sourceKind,
                   t.queryText AS queryText, t.batchSize AS batchSize, t.intervalMinutes AS intervalMinutes,
                   t.active AS topicActive, s.active AS sourceActive,
                   t.lastCollectedAt AS lastCollectedAt
            FROM Topic t JOIN t.sources s
            WHERE (:topicId IS NULL OR t.id = :topicId)
              AND (:sourceId IS NULL OR s.id = :sourceId)
              AND (:active IS NULL
                   OR (:active = TRUE AND t.active = TRUE AND s.active = TRUE)
                   OR (:active = FALSE AND (t.active = FALSE OR s.active = FALSE)))
            ORDER BY t.id ASC, s.id ASC
            """,
            countQuery = """
            SELECT COUNT(s)
            FROM Topic t JOIN t.sources s
            WHERE (:topicId IS NULL OR t.id = :topicId)
              AND (:sourceId IS NULL OR s.id = :sourceId)
              AND (:active IS NULL
                   OR (:active = TRUE AND t.active = TRUE AND s.active = TRUE)
                   OR (:active = FALSE AND (t.active = FALSE OR s.active = FALSE)))
            """)
    Page<CombinationRow> findCombinations(@Param("topicId") Long topicId,
                                          @Param("sourceId") Long sourceId,
                                          @Param("active") Boolean active,
                                          Pageable pageable);

    @Query("""
            SELECT t AS topic, s AS source
            FROM Topic t JOIN t.sources s
            WHERE t.active = TRUE
              AND s.active = TRUE
            ORDER BY t.id ASC, s.id ASC
            """)
    List<CollectionTarget> findActiveCollectionTargets();

    @Query("""
            SELECT t AS topic, s AS source
            FROM Topic t JOIN t.sources s
            WHERE t.id IN :topicIds
              AND t.active = TRUE
              AND s.active = TRUE
            ORDER BY t.id ASC, s.id ASC
            """)
    List<CollectionTarget> findActiveCollectionTargetsByTopicIds(@Param("topicIds") Collection<Long> topicIds);

    /**
     * 수집 대상 주제를 잠근다. 충돌 검사(findInProgressByTopicIds)와 실행 생성 사이에 다른 요청이
     * 끼어들면 같은 주제를 동시에 수집하게 되는데, idempotencyKey와 달리 이건 DB 제약으로 막을 수 없다.
     *
     * <p>id 순서로 잠근다. 요청마다 순서가 다르면 서로를 기다리는 교착이 생긴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT t
            FROM Topic t
            WHERE t.id IN :topicIds
            ORDER BY t.id ASC
            """)
    List<Topic> lockByIds(@Param("topicIds") Collection<Long> topicIds);

    interface LinkedSourceCount {

        Long getTopicId();

        int getLinkedSourceCount();
    }

    interface CombinationRow {

        Long getTopicId();

        String getTopicName();

        Long getSourceId();

        String getSourceName();

        String getSourceKind();

        String getQueryText();

        int getBatchSize();

        int getIntervalMinutes();

        boolean getTopicActive();

        boolean getSourceActive();

        LocalDateTime getLastCollectedAt();
    }

    interface CollectionTarget {

        Topic getTopic();

        Source getSource();
    }
}
