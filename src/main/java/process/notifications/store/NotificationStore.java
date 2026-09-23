package process.notifications.store;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;

import javax.sql.DataSource;
import java.io.Closeable;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * notifications_db.notification, and every way it is read or written (MIG-21).
 *
 * Every statement names a tenant: there is no method that reaches a row without one. The tenant
 * is the recipient's, fixed when the row is written; a reader asks in its own scope and simply
 * does not see rows filed under another.
 *
 * @author Nabeel Ahmed
 */
public class NotificationStore implements DisposableBean {

    /** The scope of a platform admin, who has no tenant. Tenant ids start at 2900. */
    public static final long PLATFORM_SCOPE = 0L;

    private static final String COLUMNS = "notification_id, tenant_id, recipient_user_id, type, severity, title, "
        + "message, link_url, is_read, read_at, date_created";

    private static final RowMapper<Notification> ROW = (rs, i) -> {
        Notification notification = new Notification();
        notification.setNotificationId(rs.getLong("notification_id"));
        notification.setTenantId(rs.getLong("tenant_id"));
        notification.setRecipientUserId(rs.getLong("recipient_user_id"));
        notification.setType(NotificationType.valueOf(rs.getString("type")));
        notification.setSeverity(NotificationSeverity.valueOf(rs.getString("severity")));
        notification.setTitle(rs.getString("title"));
        notification.setMessage(rs.getString("message"));
        notification.setLinkUrl(rs.getString("link_url"));
        notification.setRead(rs.getBoolean("is_read"));
        Timestamp readAt = rs.getTimestamp("read_at");
        notification.setReadAt(readAt == null ? null : readAt.toLocalDateTime());
        notification.setDateCreated(rs.getTimestamp("date_created").toLocalDateTime());
        return notification;
    };

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public NotificationStore(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** The scope a tenant id stands for: its own, or the platform's when there is none. */
    public static long scopeOf(Long tenantId) {
        return tenantId == null ? PLATFORM_SCOPE : tenantId;
    }

    public Notification insert(Notification notification) {
        if (notification.getTenantId() == null || notification.getRecipientUserId() == null) {
            throw new IllegalArgumentException("A notification needs a tenant scope and a recipient");
        }
        KeyHolder key = new GeneratedKeyHolder();
        this.jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement("INSERT INTO notification (tenant_id, recipient_user_id, "
                + "type, severity, title, message, link_url, is_read, read_at, date_created) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", new String[] {"notification_id"});
            insert.setLong(1, notification.getTenantId());
            insert.setLong(2, notification.getRecipientUserId());
            insert.setString(3, notification.getType().name());
            insert.setString(4, notification.getSeverity().name());
            insert.setString(5, notification.getTitle());
            insert.setString(6, notification.getMessage());
            insert.setString(7, notification.getLinkUrl());
            insert.setBoolean(8, notification.isRead());
            insert.setTimestamp(9, notification.getReadAt() == null ? null : Timestamp.valueOf(notification.getReadAt()));
            insert.setTimestamp(10, Timestamp.valueOf(notification.getDateCreated()));
            return insert;
        }, key);
        notification.setNotificationId(key.getKey().longValue());
        return notification;
    }

    /** One recipient's notifications in one scope, newest first. */
    public Page<Notification> inbox(long tenantId, long recipientUserId, boolean unreadOnly, Pageable page) {
        String where = " FROM notification WHERE tenant_id = ? AND recipient_user_id = ?" + (unreadOnly ? " AND NOT is_read" : "");
        Long total = this.jdbc.queryForObject("SELECT count(*)" + where, Long.class, tenantId, recipientUserId);
        List<Notification> rows = this.jdbc.query("SELECT " + COLUMNS + where
            + " ORDER BY date_created DESC, notification_id DESC LIMIT ? OFFSET ?",
            ROW, tenantId, recipientUserId, page.getPageSize(), page.getOffset());
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public long unreadCount(long tenantId, long recipientUserId) {
        Long count = this.jdbc.queryForObject("SELECT count(*) FROM notification WHERE tenant_id = ? AND recipient_user_id = ? "
            + "AND NOT is_read", Long.class, tenantId, recipientUserId);
        return count == null ? 0 : count;
    }

    public Optional<Notification> find(long tenantId, long notificationId) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM notification WHERE tenant_id = ? AND notification_id = ?",
            ROW, tenantId, notificationId).stream().findFirst();
    }

    /** Marks one of the recipient's own unread rows read; 0 when it is not theirs, not here, or already read. */
    public int markRead(long tenantId, long notificationId, long recipientUserId, LocalDateTime at) {
        return this.jdbc.update("UPDATE notification SET is_read = TRUE, read_at = ? WHERE tenant_id = ? "
            + "AND notification_id = ? AND recipient_user_id = ? AND NOT is_read",
            Timestamp.valueOf(at), tenantId, notificationId, recipientUserId);
    }

    public int markAllRead(long tenantId, long recipientUserId, LocalDateTime at) {
        return this.jdbc.update("UPDATE notification SET is_read = TRUE, read_at = ? WHERE tenant_id = ? "
            + "AND recipient_user_id = ? AND NOT is_read", Timestamp.valueOf(at), tenantId, recipientUserId);
    }

    @Override
    public void destroy() throws IOException {
        if (this.dataSource instanceof Closeable) {
            ((Closeable) this.dataSource).close();
        }
    }
}
