package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.NewsIssue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NewsIssueRepository extends JpaRepository<NewsIssue, Long> {

    @Override
    @EntityGraph(attributePaths = "topic")
    Optional<NewsIssue> findById(Long id);

    @Override
    @EntityGraph(attributePaths = "topic")
    List<NewsIssue> findAllById(Iterable<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT issue FROM NewsIssue issue JOIN FETCH issue.topic WHERE issue.id = :issueId")
    Optional<NewsIssue> findByIdForUpdate(@Param("issueId") Long issueId);
}
