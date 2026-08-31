package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.NewsIssue;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NewsIssueRepository extends JpaRepository<NewsIssue, Long> {

    @Override
    @EntityGraph(attributePaths = "topic")
    Optional<NewsIssue> findById(Long id);
}
