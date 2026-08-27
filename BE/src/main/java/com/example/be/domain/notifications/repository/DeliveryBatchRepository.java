package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.DeliveryBatch;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryBatchRepository extends JpaRepository<DeliveryBatch, String> {
    @EntityGraph(attributePaths = "report")
    Optional<DeliveryBatch> findByIdempotencyKey(String idempotencyKey);
}
