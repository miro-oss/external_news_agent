package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CollectionRunItemRepository extends JpaRepository<CollectionRunItem, Long> {

    List<CollectionRunItem> findByRunIdOrderByIdAsc(Long runId);

    @Query("""
            SELECT item
            FROM CollectionRunItem item
            JOIN FETCH item.run
            JOIN FETCH item.topic
            JOIN FETCH item.source
            WHERE item.run.id = :runId
            ORDER BY item.id ASC
            """)
    List<CollectionRunItem> findExecutionItemsByRunId(@Param("runId") Long runId);

    @Query("""
            SELECT DISTINCT item.topic.id
            FROM CollectionRunItem item
            WHERE item.run.id = :runId
              AND item.topic.id IN :topicIds
            ORDER BY item.topic.id ASC
            """)
    List<Long> findTopicIdsByRunIdAndTopicIdIn(@Param("runId") Long runId,
                                               @Param("topicIds") Collection<Long> topicIds);

    /**
     * 충돌한 실행이 여럿일 수 있다. run A가 주제 1을, run B가 주제 2를 수집 중이면 요청 [1, 2]는
     * 둘 다와 부딪힌다. 명세의 conflictTopicIds가 복수형인 만큼 전부 모아서 내려준다.
     */
    @Query("""
            SELECT DISTINCT item.topic.id
            FROM CollectionRunItem item
            WHERE item.run.id IN :runIds
              AND item.topic.id IN :topicIds
            ORDER BY item.topic.id ASC
            """)
    List<Long> findTopicIdsByRunIdInAndTopicIdIn(@Param("runIds") Collection<Long> runIds,
                                                 @Param("topicIds") Collection<Long> topicIds);

    Optional<CollectionRunItem> findFirstBySourceIdOrderByRunStartedAtDescRunIdDescIdDesc(Long sourceId);
}
