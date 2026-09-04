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
    Optional<NewsReport> findFirstByReportScopeAndReportStatusNotOrderByGeneratedAtDescIdDesc(
            ReportScope scope, ReportStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT report FROM NewsReport report WHERE report.id = :reportId")
    Optional<NewsReport> findByIdForUpdate(@Param("reportId") Long id);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findFirstByReportStatusNotOrderByGeneratedAtDescIdDesc(
            ReportStatus reportStatus);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findByIdAndReportStatusNot(
            Long id, ReportStatus reportStatus);
}
