package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.ChannelType;
import com.example.be.domain.notifications.entity.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NotificationChannelRepository extends JpaRepository<NotificationChannel, Long> {
    List<NotificationChannel> findAllByOrderByIdAsc();
    List<NotificationChannel> findAllByActiveOrderByIdAsc(boolean active);
    Optional<NotificationChannel> findByChannelType(ChannelType channelType);
}
