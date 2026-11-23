package com.fincore.notification.repository;

import com.fincore.notification.entity.Notification;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    long countByEventId(UUID eventId);
}
