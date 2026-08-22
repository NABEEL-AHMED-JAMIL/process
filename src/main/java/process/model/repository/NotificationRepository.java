package process.model.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.Notification;
import java.time.LocalDateTime;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientUserIdOrderByDateCreatedDesc(Long recipientUserId, Pageable pageable);

    Page<Notification> findByRecipientUserIdAndReadOrderByDateCreatedDesc(Long recipientUserId, boolean read, Pageable pageable);

    long countByRecipientUserIdAndReadFalse(Long recipientUserId);

    @Transactional
    @Modifying
    @Query("update Notification n set n.read = true, n.readAt = ?2 where n.notificationId = ?1 and n.recipientUserId = ?3 and n.read = false")
    int markRead(Long notificationId, LocalDateTime readAt, Long recipientUserId);

    @Transactional
    @Modifying
    @Query("update Notification n set n.read = true, n.readAt = ?2 where n.recipientUserId = ?1 and n.read = false")
    int markAllRead(Long recipientUserId, LocalDateTime readAt);

}
