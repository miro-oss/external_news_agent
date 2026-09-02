package com.example.be.domain.sources.repository;

import com.example.be.domain.sources.entity.Source;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SourceRepository extends JpaRepository<Source, Long>, JpaSpecificationExecutor<Source> {

    boolean existsBySourceKindAndUrlTemplate(String sourceKind, String urlTemplate);

    boolean existsBySourceKindAndUrlTemplateAndIdNot(String sourceKind, String urlTemplate, Long id);

    /**
     * 목록 조회에서 소스마다 주제 컬렉션을 지연 로딩하지 않도록 연결 수만 한 번에 센다.
     */
    @Query("SELECT s.id AS sourceId, SIZE(s.topics) AS linkedTopicCount FROM Source s WHERE s.id IN :sourceIds")
    List<LinkedTopicCount> countLinkedTopics(@Param("sourceIds") Collection<Long> sourceIds);

    @Query("""
            SELECT source
            FROM Source source
            JOIN source.topics topic
            WHERE topic.id = :topicId AND source.active = true
            ORDER BY source.id ASC
            """)
    List<Source> findActiveByTopicId(@Param("topicId") Long topicId);

    interface LinkedTopicCount {

        Long getSourceId();

        int getLinkedTopicCount();
    }
}
