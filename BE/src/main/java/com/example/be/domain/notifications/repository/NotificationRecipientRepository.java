package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.NotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationRecipientRepository
        extends JpaRepository<NotificationRecipient, Long>, JpaSpecificationExecutor<NotificationRecipient> {
}
