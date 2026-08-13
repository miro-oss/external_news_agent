package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * 기사 목록 조회의 runId/topicId/sourceId/changeType 필터가 이 테이블 위에서 돈다.
 * 복합 조건은 후속 이슈에서 Specification으로 조립한다.
 */
public interface CollectionRunArticleRepository
        extends JpaRepository<CollectionRunArticle, Long>, JpaSpecificationExecutor<CollectionRunArticle> {

    List<CollectionRunArticle> findByRunIdOrderByIdAsc(Long runId);

    List<CollectionRunArticle> findByRunIdAndChangeTypeOrderByIdAsc(Long runId, ChangeType changeType);

    /**
     * 한 기사가 실행을 거치며 어떻게 바뀌어 왔는지. 최신 관측이 마지막이다.
     */
    List<CollectionRunArticle> findByArticleIdOrderByObservedAtAsc(Long articleId);

    long countByRunIdAndChangeType(Long runId, ChangeType changeType);
}
