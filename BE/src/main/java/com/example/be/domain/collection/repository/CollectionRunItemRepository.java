package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRunItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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
     * 소스 상세의 lastCollectedAt / lastRunStatus를 채우는 자리다(M2에서 null로 비워둔 항목).
     */
    List<CollectionRunItem> findByRunIdInOrderByIdAsc(List<Long> runIds);
}
