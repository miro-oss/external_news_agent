package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;

/**
 * 기사 목록 조회의 runId/topicId/sourceId/changeType 필터가 이 테이블 위에서 돈다.
 * M4의 {@code FindingSpecification}은 이 관측을 서브쿼리해 분석 결과와 실행 당시 조합을 함께 필터링한다.
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

    /** 이번 실행에서 관측한 고유 기사별 최신 수집 상태를 보고서 통계에 제공한다. */
    @Query("""
            SELECT observation.article.id AS articleId,
                   observation.article.fetchStatus AS fetchStatus
            FROM CollectionRunArticle observation
            WHERE observation.run.id = :runId
            ORDER BY observation.id ASC
            """)
    List<ArticleFetchStatus> findArticleFetchStatusesByRunId(@Param("runId") Long runId);

    /**
     * 이번 실행의 모든 관측을 분석 후보로 읽는다. UNCHANGED는 이전 LLM finding이 있을 때만 재사용한다.
     * 본문과 주제·소스까지 이 조회에서 붙여 트랜잭션 밖에서도 안전하게 읽는다.
     */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE observation.run.id = :runId
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findAnalysisTargetsByRunId(@Param("runId") Long runId);

    /** 이번 실행에서 전문 재수집에 성공한 기사는 UNCHANGED 관측이어도 다시 분석한다. */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE observation.run.id = :runId
              AND article.id IN :articleIds
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findAnalysisTargetsByRunIdAndArticleIdIn(
            @Param("runId") Long runId,
            @Param("articleIds") Set<Long> articleIds);

    interface ArticleFetchStatus {

        Long getArticleId();

        FetchStatus getFetchStatus();
    }

}
