package com.example.be.domain.collection.repository;

import com.example.be.domain.collection.entity.CollectionDispatchLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CollectionDispatchLockRepository extends JpaRepository<CollectionDispatchLock, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gate FROM CollectionDispatchLock gate WHERE gate.id = 1")
    CollectionDispatchLock lockDispatcher();
}
