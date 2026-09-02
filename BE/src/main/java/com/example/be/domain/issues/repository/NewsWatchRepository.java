package com.example.be.domain.issues.repository;

import com.example.be.domain.issues.entity.NewsWatch;
import com.example.be.domain.issues.entity.WatchType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface NewsWatchRepository extends JpaRepository<NewsWatch, Long> {

    Optional<NewsWatch> findByIssueIdAndWatchType(Long issueId, WatchType watchType);

    List<NewsWatch> findByIssueIdOrderByIdAsc(Long issueId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT watch
            FROM NewsWatch watch
            WHERE watch.issue.id = :issueId
              AND watch.active = true
              AND watch.watchType IN (
                    com.example.be.domain.issues.entity.WatchType.BREAKING,
                    com.example.be.domain.issues.entity.WatchType.HIGH_SENSITIVITY)
              AND watch.expiresAt > :now
              AND (watch.cooldownUntil IS NULL OR watch.cooldownUntil <= :now)
            ORDER BY watch.id ASC
            """)
    List<NewsWatch> findEligibleForNotification(
            @Param("issueId") Long issueId,
            @Param("now") LocalDateTime now);
}
