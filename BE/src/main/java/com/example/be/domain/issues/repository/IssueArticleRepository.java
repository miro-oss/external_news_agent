package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.IssueArticle;
import com.example.be.domain.issues.entity.IssueArticleRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;

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

    Optional<IssueArticle> findFirstByIssueIdAndRole(Long issueId, IssueArticleRole role);
}
