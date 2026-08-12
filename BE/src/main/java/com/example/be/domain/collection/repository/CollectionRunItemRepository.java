package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionRunItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CollectionRunItemRepository extends JpaRepository<CollectionRunItem, Long> {

    List<CollectionRunItem> findByRunIdOrderByIdAsc(Long runId);

    /**
     * 소스 상세의 lastCollectedAt / lastRunStatus를 채우는 자리다(M2에서 null로 비워둔 항목).
     */
    List<CollectionRunItem> findByRunIdInOrderByIdAsc(List<Long> runIds);
}
