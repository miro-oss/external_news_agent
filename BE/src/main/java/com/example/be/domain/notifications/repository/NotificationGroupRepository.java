package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.NotificationGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface NotificationGroupRepository
        extends JpaRepository<NotificationGroup, Long>, JpaSpecificationExecutor<NotificationGroup> {
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, Long id);
    List<NotificationGroup> findAllByActiveOrderByIdAsc(boolean active);
    Optional<NotificationGroup> findByIdAndActive(Long id, boolean active);
}
