package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.DeliveryLog;
import com.example.be.domain.notifications.entity.DeliveryStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, Long>, JpaSpecificationExecutor<DeliveryLog> {
    @Override
    @EntityGraph(attributePaths = {"report", "report.run", "recipient"})
    Page<DeliveryLog> findAll(Specification<DeliveryLog> specification, Pageable pageable);

    @EntityGraph(attributePaths = {"batch", "batch.report", "recipient"})
    List<DeliveryLog> findAllByBatchIdOrderByIdAsc(String batchId);

    @Query("""
            select log.report.id as reportId, log.status as status, count(log.id) as count
            from DeliveryLog log
            where log.report.id in :reportIds
            group by log.report.id, log.status
            """)
    List<ReportDeliveryStatusCount> countStatusesByReportIds(@Param("reportIds") List<Long> reportIds);

    interface ReportDeliveryStatusCount {
        Long getReportId();
        DeliveryStatus getStatus();
        long getCount();
    }
}
