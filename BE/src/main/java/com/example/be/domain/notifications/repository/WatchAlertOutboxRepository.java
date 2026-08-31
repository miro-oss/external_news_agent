package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface WatchAlertOutboxRepository extends JpaRepository<WatchAlertOutbox, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT alert
            FROM WatchAlertOutbox alert
            WHERE alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PENDING
               OR (alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PROCESSING
                   AND alert.processingStartedAt <= :staleBefore)
            ORDER BY alert.id ASC
            """)
    List<WatchAlertOutbox> findClaimable(@Param("staleBefore") LocalDateTime staleBefore);
}
