package process.model.pojo;

import javax.persistence.Column;
import javax.persistence.Embeddable;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Table;
import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * One person's exception to their access profile for one page: open (allowed) or withheld.
 *
 * Only ever a real difference from the profile -- the service deletes a row that would say what
 * the profile already says -- so the table reads as the list of exceptions and nothing else.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "user_page_access")
public class UserPageAccess {

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "app_user_id", nullable = false)
        private Long appUserId;
        @Column(name = "page_key", nullable = false, length = 64)
        private String pageKey;

        public Key() {}
        public Key(Long appUserId, String pageKey) { this.appUserId = appUserId; this.pageKey = pageKey; }
        public Long getAppUserId() { return appUserId; }
        public String getPageKey() { return pageKey; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return Objects.equals(appUserId, k.appUserId) && Objects.equals(pageKey, k.pageKey);
        }
        @Override public int hashCode() { return Objects.hash(appUserId, pageKey); }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated;

    @Column(name = "created_by")
    private Long createdBy;

    public UserPageAccess() {}

    public UserPageAccess(Long appUserId, String pageKey, boolean allowed, Long createdBy) {
        this.id = new Key(appUserId, pageKey);
        this.allowed = allowed;
        this.createdBy = createdBy;
        this.dateCreated = new Timestamp(System.currentTimeMillis());
    }

    public Key getId() { return id; }
    public Long getAppUserId() { return id == null ? null : id.getAppUserId(); }
    public String getPageKey() { return id == null ? null : id.getPageKey(); }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public Timestamp getDateCreated() { return dateCreated; }
    public Long getCreatedBy() { return createdBy; }
}
