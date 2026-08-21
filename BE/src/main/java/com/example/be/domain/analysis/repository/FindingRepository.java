package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.AnalysisSource;
import com.example.be.domain.analysis.entity.Finding;
import com.example.be.domain.analysis.entity.RiskLevel;
import com.example.be.domain.collection.entity.ChangeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long>, JpaSpecificationExecutor<Finding> {

    @Override
    @EntityGraph(attributePaths = {"article", "article.topic", "article.source"})
    Page<Finding> findAll(Specification<Finding> specification, Pageable pageable);

    boolean existsByRunIdAndArticleId(Long runId, Long articleId);

    Optional<Finding> findFirstByArticleIdOrderByIdDesc(Long articleId);

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
            JOIN FETCH article.topic
            JOIN FETCH article.source
            WHERE finding.run.id = :runId
            ORDER BY finding.id ASC
            """)
    List<Finding> findForReportByRunId(@Param("runId") Long runId);

    @Query("""
            SELECT finding.riskLevel AS riskLevel,
                   finding.category AS category,
                   finding.changeType AS changeType,
                   COUNT(finding) AS findingCount
            FROM Finding finding
            WHERE finding.run.id = :runId
            GROUP BY finding.riskLevel, finding.category, finding.changeType
            """)
    List<ReportStatsCount> countStatsByRunId(@Param("runId") Long runId);

    @Query("""
            SELECT finding.run.id AS runId,
                   COUNT(finding) AS findingCount,
                   SUM(CASE WHEN finding.riskLevel = com.example.be.domain.analysis.entity.RiskLevel.HIGH
                            THEN 1 ELSE 0 END) AS highRiskCount
            FROM Finding finding
            WHERE finding.run.id IN :runIds
            GROUP BY finding.run.id
            """)
    List<ReportCount> countForReports(@Param("runIds") Collection<Long> runIds);

    interface ReportCount {

        Long getRunId();

        long getFindingCount();

        long getHighRiskCount();
    }

    interface ReportStatsCount {

        RiskLevel getRiskLevel();

        String getCategory();

        ChangeType getChangeType();

        long getFindingCount();
    }
}
