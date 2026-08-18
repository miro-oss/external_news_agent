package com.example.be.domain.reports.repository;

import com.example.be.domain.reports.entity.NewsReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface NewsReportRepository
        extends JpaRepository<NewsReport, Long>, JpaSpecificationExecutor<NewsReport> {

    @Override
    @EntityGraph(attributePaths = "run")
    Page<NewsReport> findAll(Specification<NewsReport> specification, Pageable pageable);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findByRunId(Long runId);

    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findFirstByOrderByGeneratedAtDescIdDesc();

    @Override
    @EntityGraph(attributePaths = "run")
    Optional<NewsReport> findById(Long id);
}
