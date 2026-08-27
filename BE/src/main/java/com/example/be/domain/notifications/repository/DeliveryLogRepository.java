package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long>, JpaSpecificationExecutor<DeliveryLog> {
    List<DeliveryLog> findAllByBatchIdOrderByIdAsc(String batchId);
    boolean existsByReportIdAndStatus(Long reportId, DeliveryStatus status);
    boolean existsByReportId(Long reportId);
}
