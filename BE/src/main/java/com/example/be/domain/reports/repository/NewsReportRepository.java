package com.example.be.domain.reports.repository;

import com.example.be.domain.reports.entity.NewsReport;
import com.example.be.domain.reports.entity.ReportScope;
import com.example.be.domain.reports.entity.ReportStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface NewsReportRepository
        extends JpaRepository<NewsReport, Long>, JpaSpecificationExecutor<NewsReport> {

    @Override
    @EntityGraph(attributePaths = "run")
    Page<NewsReport> findAll(Specification<NewsReport> specification, Pageable pageable);

    @EntityGraph(attributePaths = "run")
    @Query("SELECT report FROM NewsReport report WHERE report.run.id = :runId")
    Optional<NewsReport> findByRunId(@Param("runId") Long runId);

    Optional<NewsReport> findByReportScopeAndReportDate(ReportScope scope, LocalDate date);

    List<NewsReport> findByReportScopeAndReportStatusAndGeneratedAtBefore(
            ReportScope scope, ReportStatus status, LocalDateTime before);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findFirstByReportScopeAndReportStatusNotAndDeletedAtIsNullOrderByGeneratedAtDescIdDesc(
            ReportScope scope, ReportStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT report FROM NewsReport report WHERE report.id = :reportId")
    Optional<NewsReport> findByIdForUpdate(@Param("reportId") Long id);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findFirstByReportStatusNotAndDeletedAtIsNullOrderByGeneratedAtDescIdDesc(
            ReportStatus reportStatus);

    @EntityGraph(attributePaths = "run")
    @Query("""
            SELECT report FROM NewsReport report
            WHERE report.id = :id AND report.reportStatus <> :reportStatus AND report.deletedAt IS NULL
            """)
    Optional<NewsReport> findByIdAndReportStatusNot(
            @Param("id") Long id, @Param("reportStatus") ReportStatus reportStatus);

    /** 숨긴 보고서도 생성 이력이므로 일일 통합의 원본 보고서 수에는 포함한다. */
    @Query("""
            SELECT COUNT(report) FROM NewsReport report
            WHERE report.reportScope = com.example.be.domain.reports.entity.ReportScope.RUN
              AND report.reportStatus <> com.example.be.domain.reports.entity.ReportStatus.PENDING
              AND report.run.id IN :runIds AND report.generatedAt <= :generatedBefore
            """)
    long countCompletedSourceReports(@Param("runIds") Collection<Long> runIds,
                                     @Param("generatedBefore") LocalDateTime generatedBefore);
}
