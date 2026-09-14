package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.WatchAlertOutbox;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface WatchAlertOutboxRepository extends JpaRepository<WatchAlertOutbox, Long> {

    @Query("SELECT COALESCE(MAX(alert.id), 0) FROM WatchAlertOutbox alert")
    long findScanUpperBound();

    @Query("""
            SELECT alert.id
            FROM WatchAlertOutbox alert
            WHERE alert.id > :afterId
              AND alert.id <= :upToId
              AND (alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PENDING
                OR (alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PROCESSING
                    AND alert.processingStartedAt <= :staleBefore))
            ORDER BY alert.id ASC
            """)
    List<Long> findClaimableIds(@Param("staleBefore") LocalDateTime staleBefore,
                                @Param("afterId") Long afterId,
                                @Param("upToId") Long upToId,
                                Pageable pageable);

    // Keep pagination outside the locking query: Oracle cannot combine FETCH FIRST and FOR UPDATE.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT alert
            FROM WatchAlertOutbox alert
            WHERE alert.id IN :ids
              AND (alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PENDING
                OR (alert.status = com.example.be.domain.notifications.entity.WatchAlertDeliveryStatus.PROCESSING
                    AND alert.processingStartedAt <= :staleBefore))
            ORDER BY alert.id ASC
            """)
    List<WatchAlertOutbox> findClaimableByIdsForUpdate(@Param("ids") Collection<Long> ids,
                                                     @Param("staleBefore") LocalDateTime staleBefore);
}
