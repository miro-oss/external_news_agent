package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.NotificationGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationGroupRepository
        extends JpaRepository<NotificationGroup, Long>, JpaSpecificationExecutor<NotificationGroup> {
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, Long id);
}
