package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.collection.entity.ChangeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long>, JpaSpecificationExecutor<Finding> {

    @Override
    @EntityGraph(attributePaths = {"article", "article.topic", "article.source"})
    Page<Finding> findAll(Specification<Finding> specification, Pageable pageable);

    Optional<Finding> findFirstByArticleIdOrderByIdDesc(Long articleId);

    @Query("""
            SELECT finding
            FROM Finding finding
            JOIN FETCH finding.article article
            JOIN FETCH finding.run
            WHERE article.id IN :articleIds
              AND finding.id = (
                  SELECT MAX(latest.id)
                  FROM Finding latest
                  WHERE latest.article.id = article.id
              )
            """)
    List<Finding> findLatestByArticleIds(@Param("articleIds") Collection<Long> articleIds);

    @Query("""
            SELECT finding
            FROM Finding finding
            JOIN FETCH finding.article article
            JOIN FETCH finding.run
            WHERE article.topic.id = :topicId
              AND article.publishedAt IS NOT NULL
              AND article.publishedAt >= :since
              AND article.publishedAt < :before
              AND finding.analysisSource IN :analysisSources
              AND finding.id = (
                  SELECT MAX(latest.id)
                  FROM Finding latest
                  WHERE latest.article.id = article.id
              )
            ORDER BY article.publishedAt DESC, finding.id DESC
            """)
    List<Finding> findInsightHistoryCandidates(
            @Param("topicId") Long topicId,
            @Param("since") OffsetDateTime since,
            @Param("before") OffsetDateTime before,
            @Param("analysisSources") Collection<AnalysisSource> analysisSources,
            Pageable pageable);

    @Query("""
            SELECT finding
            FROM Finding finding
            JOIN FETCH finding.article article
            WHERE article.id IN :articleIds
              AND finding.analysisSource = :analysisSource
              AND finding.analysisInputHash IN :analysisInputHashes
              AND finding.promptVersion = :promptVersion
              AND finding.llmProvider = :provider
              AND finding.llmModel = :model
            ORDER BY finding.id DESC
            """)
    List<Finding> findReusableSources(
            @Param("articleIds") Collection<Long> articleIds,
            @Param("analysisSource") AnalysisSource analysisSource,
            @Param("analysisInputHashes") Collection<String> analysisInputHashes,
            @Param("promptVersion") String promptVersion,
            @Param("provider") String provider,
            @Param("model") String model);

    Optional<Finding> findByRunIdAndArticleId(Long runId, Long articleId);

    @Query("""
            SELECT finding
            FROM Finding finding
            JOIN FETCH finding.article article
            WHERE finding.run.id = :runId
              AND article.id IN :articleIds
            ORDER BY finding.id ASC
            """)
    List<Finding> findByRunIdAndArticleIdIn(
            @Param("runId") Long runId,
            @Param("articleIds") Collection<Long> articleIds);

    @Query("""
            SELECT finding
            FROM Finding finding
            JOIN FETCH finding.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE finding.run.id = :runId
            ORDER BY finding.id ASC
            """)
    List<Finding> findForReportByRunId(@Param("runId") Long runId);

    @Query("""
            SELECT finding FROM Finding finding
            JOIN FETCH finding.run
            JOIN FETCH finding.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE finding.id IN :ids
            """)
    List<Finding> findForReportByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
            SELECT finding FROM Finding finding
            JOIN FETCH finding.run run
            JOIN FETCH finding.article article
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE run.startedAt >= :since AND run.startedAt < :before
              AND run.status NOT IN (com.example.be.domain.collection.entity.RunStatus.PENDING,
                                     com.example.be.domain.collection.entity.RunStatus.RUNNING)
            ORDER BY run.startedAt DESC, finding.id DESC
            """)
    List<Finding> findDailyReportCandidates(
            @Param("since") LocalDateTime since,
            @Param("before") LocalDateTime before);

    @Query("""
            SELECT finding.category AS category,
                   finding.changeType AS changeType,
                   COUNT(finding) AS findingCount,
                   SUM(CASE WHEN finding.sensitivity.score >= :highThreshold
                            THEN 1 ELSE 0 END) AS highSensitivityCount,
                   SUM(CASE WHEN finding.sensitivity.score >= :mediumThreshold
                                 AND finding.sensitivity.score < :highThreshold
                            THEN 1 ELSE 0 END) AS mediumSensitivityCount,
                   SUM(CASE WHEN finding.sensitivity.score < :mediumThreshold
                            THEN 1 ELSE 0 END) AS lowSensitivityCount
            FROM Finding finding
            WHERE finding.run.id = :runId
            GROUP BY finding.category, finding.changeType
            """)
    List<ReportStatsCount> countStatsByRunId(
            @Param("runId") Long runId,
            @Param("mediumThreshold") BigDecimal mediumThreshold,
            @Param("highThreshold") BigDecimal highThreshold);

    @Query("""
            SELECT finding.run.id AS runId,
                   COUNT(finding) AS findingCount,
                   SUM(CASE WHEN finding.sensitivity.score >= :highThreshold
                            THEN 1 ELSE 0 END) AS highSensitivityCount
            FROM Finding finding
            WHERE finding.run.id IN :runIds
            GROUP BY finding.run.id
            """)
    List<ReportCount> countForReports(
            @Param("runIds") Collection<Long> runIds,
            @Param("highThreshold") BigDecimal highThreshold);

    interface ReportCount {

        Long getRunId();

        long getFindingCount();

        long getHighSensitivityCount();
    }

    interface ReportStatsCount {

        String getCategory();

        ChangeType getChangeType();

        long getFindingCount();

        long getHighSensitivityCount();

        long getMediumSensitivityCount();

        long getLowSensitivityCount();
    }
}
