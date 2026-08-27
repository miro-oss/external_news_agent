package com.example.be.domain.notifications.repository;

import com.example.be.domain.notifications.entity.RecipientDestination;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecipientDestinationRepository extends JpaRepository<RecipientDestination, Long> {
    boolean existsByChannelIdAndAddressAndRecipientIdNot(Long channelId, String address, Long recipientId);
    boolean existsByChannelIdAndAddress(Long channelId, String address);
}
