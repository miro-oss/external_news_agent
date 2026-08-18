package com.example.be.domain.analysis.repository;

import com.example.be.domain.analysis.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface FindingRepository extends JpaRepository<Finding, Long>, JpaSpecificationExecutor<Finding> {

    boolean existsByRunIdAndArticleId(Long runId, Long articleId);

    Optional<Finding> findFirstByArticleIdOrderByIdDesc(Long articleId);

    Optional<Finding> findByRunIdAndArticleId(Long runId, Long articleId);
}
