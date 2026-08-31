package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueArticle;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import java.util.Set;

public interface IssueArticleRepository extends JpaRepository<IssueArticle, Long> {

    boolean existsByIssueIdAndArticleId(Long issueId, Long articleId);

    Optional<IssueArticle> findByIssueIdAndArticleId(Long issueId, Long articleId);

    @EntityGraph(attributePaths = {"issue", "issue.topic", "article", "article.source", "article.contentGroup"})
    List<IssueArticle> findByIssueIdOrderByJoinedAtAsc(Long issueId);

    @EntityGraph(attributePaths = {"issue", "issue.topic", "article", "article.source", "article.contentGroup"})
    List<IssueArticle> findByArticleIdOrderByIssueIdAsc(Long articleId);

    @Query("""
            SELECT membership
            FROM IssueArticle membership
            JOIN FETCH membership.issue issue
            JOIN FETCH issue.topic
            JOIN FETCH membership.article article
            JOIN FETCH article.source
            LEFT JOIN FETCH article.contentGroup
            WHERE article.id IN :articleIds
            ORDER BY issue.id ASC, membership.id ASC
            """)
    List<IssueArticle> findByArticleIds(@Param("articleIds") Collection<Long> articleIds);

    @Query("""
            SELECT membership
            FROM IssueArticle membership
            JOIN FETCH membership.issue issue
            JOIN FETCH issue.topic
            JOIN FETCH membership.article article
            JOIN FETCH article.source
            LEFT JOIN FETCH article.contentGroup
            WHERE issue.topic.id IN :topicIds
              AND issue.lastSeenAt >= :since
            ORDER BY issue.id ASC, membership.id ASC
            """)
    List<IssueArticle> findRecentByTopicIds(
            @Param("topicIds") Collection<Long> topicIds,
            @Param("since") OffsetDateTime since);

    /** 이번 실행에서 새 기사나 갱신 기사가 붙은 이슈의 대표를 반환한다. 대표 자체가 이번 run에 없어도 포함한다. */
    @Query("""
            SELECT representative
            FROM IssueArticle representative
            JOIN FETCH representative.article representativeArticle
            JOIN FETCH representativeArticle.topic
            JOIN FETCH representativeArticle.source
            WHERE representative.role = com.example.be.domain.issues.entity.IssueArticleRole.REPRESENTATIVE
              AND representative.issue.id IN (
                  SELECT observedMembership.issue.id
                  FROM IssueArticle observedMembership
                  JOIN CollectionRunArticle observation ON observation.article = observedMembership.article
                  WHERE observation.run.id = :runId
                    AND observation.topic = observedMembership.issue.topic
                    AND observation.changeType IN (
                        com.example.be.domain.collection.entity.ChangeType.NEW,
                        com.example.be.domain.collection.entity.ChangeType.UPDATED
                    )
              )
            ORDER BY representative.id ASC
            """)
    List<IssueArticle> findRepresentativesForRun(@Param("runId") Long runId);

    /** 전문을 새로 확보한 멤버가 있는 이슈도 대표 분석을 갱신한다. */
    @Query("""
            SELECT representative
            FROM IssueArticle representative
            JOIN FETCH representative.article representativeArticle
            JOIN FETCH representativeArticle.topic
            JOIN FETCH representativeArticle.source
            WHERE representative.role = com.example.be.domain.issues.entity.IssueArticleRole.REPRESENTATIVE
              AND representative.issue.id IN (
                  SELECT observedMembership.issue.id
                  FROM IssueArticle observedMembership
                  JOIN CollectionRunArticle observation ON observation.article = observedMembership.article
                  WHERE observation.run.id = :runId
                    AND observation.article.id IN :articleIds
                    AND observation.topic = observedMembership.issue.topic
              )
            ORDER BY representative.id ASC
            """)
    List<IssueArticle> findRepresentativesForRunAndObservedArticleIdIn(
            @Param("runId") Long runId,
            @Param("articleIds") Set<Long> articleIds);

}
