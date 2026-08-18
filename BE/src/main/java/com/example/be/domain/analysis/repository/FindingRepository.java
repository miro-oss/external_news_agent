package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.Finding;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long>, JpaSpecificationExecutor<Finding> {

    @Override
    @EntityGraph(attributePaths = {"article", "article.topic", "article.source"})
    Page<Finding> findAll(Specification<Finding> specification, Pageable pageable);

    boolean existsByRunIdAndArticleId(Long runId, Long articleId);

    Optional<Finding> findFirstByArticleIdOrderByIdDesc(Long articleId);

    Optional<Finding> findByRunIdAndArticleId(Long runId, Long articleId);
}
