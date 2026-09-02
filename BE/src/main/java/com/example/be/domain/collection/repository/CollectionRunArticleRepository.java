package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.ChangeType;
import com.example.be.domain.collection.entity.CollectionRunArticle;
import com.example.be.domain.collection.entity.FetchStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

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

    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.source
            WHERE observation.run.id = :runId AND article.id = :articleId
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findForEnrichment(
            @Param("runId") Long runId,
            @Param("articleId") Long articleId);

    long countByRunIdAndChangeType(Long runId, ChangeType changeType);

    /** run 커버리지는 기사×주제 관측을 분모로 삼는다. */
    @Query("""
            SELECT observation.article.id AS articleId,
                   observation.topic.id AS topicId
            FROM CollectionRunArticle observation
            WHERE observation.run.id = :runId
            ORDER BY observation.id ASC
            """)
    List<CoverageObservation> findCoverageObservationsByRunId(@Param("runId") Long runId);

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
     * 클러스터 계산용 값 복사를 마치면 트랜잭션 밖에서 비교할 수 있게 필요한 연관을 한 번에 붙인다.
     * Article.body가 CLOB이므로 Oracle ORA-22848을 피하기 위해 이 쿼리에 DISTINCT를 추가하지 않는다.
     */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH observation.topic topic
            JOIN FETCH article.source source
            LEFT JOIN FETCH article.contentGroup
            WHERE observation.run.id = :runId
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findClusterTargetsByRunId(@Param("runId") Long runId);

    /** 이슈 대표만 분석한다. 멤버의 상세 조회는 대표 finding을 재사용한다. */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            JOIN FETCH observation.topic
            JOIN IssueArticle membership ON membership.article = article
            WHERE observation.run.id = :runId
              AND membership.issue.topic = observation.topic
              AND membership.role = com.example.be.domain.issues.entity.IssueArticleRole.REPRESENTATIVE
              AND observation.changeType IN (
                  com.example.be.domain.collection.entity.ChangeType.NEW,
                  com.example.be.domain.collection.entity.ChangeType.UPDATED
              )
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findRepresentativeAnalysisTargetsByRunId(@Param("runId") Long runId);

    /** 전문을 새로 확보했어도 이슈 대표일 때만 재분석한다. */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            JOIN FETCH observation.topic
            JOIN IssueArticle membership ON membership.article = article
            WHERE observation.run.id = :runId
              AND article.id IN :articleIds
              AND membership.issue.topic = observation.topic
              AND membership.role = com.example.be.domain.issues.entity.IssueArticleRole.REPRESENTATIVE
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findRepresentativeAnalysisTargetsByRunIdAndArticleIdIn(
            @Param("runId") Long runId,
            @Param("articleIds") Collection<Long> articleIds);

    /** 클러스터링이 실패했을 때 수집 결과 분석까지 잃지 않도록 쓰는 레거시 대상 조회. */
    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            JOIN FETCH observation.topic
            WHERE observation.run.id = :runId
              AND observation.changeType IN (
                  com.example.be.domain.collection.entity.ChangeType.NEW,
                  com.example.be.domain.collection.entity.ChangeType.UPDATED
              )
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findUnclusteredAnalysisTargetsByRunId(@Param("runId") Long runId);

    @Query("""
            SELECT observation
            FROM CollectionRunArticle observation
            JOIN FETCH observation.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            JOIN FETCH observation.topic
            WHERE observation.run.id = :runId
              AND article.id IN :articleIds
            ORDER BY observation.id ASC
            """)
    List<CollectionRunArticle> findUnclusteredAnalysisTargetsByRunIdAndArticleIdIn(
            @Param("runId") Long runId,
            @Param("articleIds") Collection<Long> articleIds);

    interface ArticleFetchStatus {

        Long getArticleId();

        FetchStatus getFetchStatus();
    }

    interface CoverageObservation {

        Long getArticleId();

        Long getTopicId();
    }

}
