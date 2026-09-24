package process.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

/**
 * user_directory: Core's projection of Identity's people, for the names on audit stamps (MIG-153).
 *
 * Fed by platform.identity.user.v1 and by UserNameResolver writing back what it had to ask Identity. Each row
 * carries the instant its state was true at Identity; a write that is not newer changes nothing, so events
 * redelivered late or out of order never roll a name back. Names are for reading: nothing decides access
 * from this table.
 *
 * @author Nabeel Ahmed
 */
@Component
public class UserDirectory {

    /** One person as the directory holds them: five columns and a timestamp. */
    public static final class Entry {
        private final Long appUserId;
        private final Long tenantId;
        private final String username;
        private final String fullName;
        private final String status;
        private final Instant updatedAt;

        public Entry(Long appUserId, Long tenantId, String username, String fullName, String status, Instant updatedAt) {
            this.appUserId = Objects.requireNonNull(appUserId, "appUserId");
            this.tenantId = tenantId;
            this.username = Objects.requireNonNull(username, "username");
            this.fullName = fullName;
            this.status = Objects.requireNonNull(status, "status");
            this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }

        /** What IdentityPort answered, true as of {@code asked}: the instant the question was put, not answered. */
        public static Entry of(IdentityPort.Person person, Instant asked) {
            return new Entry(person.getAppUserId(), person.getTenantId(), person.getUsername(), person.getFullName(),
                person.getStatus(), asked);
        }

        public Long getAppUserId() { return this.appUserId; }
        public Long getTenantId() { return this.tenantId; }
        public String getUsername() { return this.username; }
        public String getFullName() { return this.fullName; }
        public String getStatus() { return this.status; }
        public Instant getUpdatedAt() { return this.updatedAt; }

        /** IdentityPort.Person's rule: the full name where there is one, else the username. */
        public String getDisplayName() {
            return this.fullName == null || this.fullName.trim().isEmpty() ? this.username : this.fullName.trim();
        }

        /** Whether this says the same about the person as that, whenever each was true. */
        public boolean sameAs(IdentityPort.Person person) {
            return Objects.equals(this.tenantId, person.getTenantId()) && Objects.equals(this.username, person.getUsername())
                && Objects.equals(this.fullName, person.getFullName()) && Objects.equals(this.status, person.getStatus());
        }
    }

    private static final Logger logger = LoggerFactory.getLogger(UserDirectory.class);

    private final JdbcTemplate jdbc;
    private final Executor writeBacks;

    /**
     * Write-backs run on a thread of their own, outside the transaction of whoever asked for the names: a
     * failed INSERT inside a caller's transaction would abort it, and a read-only one refuses writes at all.
     */
    @Autowired
    public UserDirectory(JdbcTemplate jdbc) {
        this(jdbc, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "user-directory-write-back");
            thread.setDaemon(true);
            return thread;
        }));
    }

    public UserDirectory(JdbcTemplate jdbc, Executor writeBacks) {
        this.jdbc = jdbc;
        this.writeBacks = writeBacks;
    }

    /** What UserNameResolver had to ask Identity, kept for next time -- later, on the write-back thread. Best effort. */
    public void writeBack(Collection<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<Entry> copy = new ArrayList<>(entries);
        try {
            this.writeBacks.execute(() -> {
                for (Entry entry : copy) {
                    try {
                        this.apply(entry);
                    } catch (RuntimeException failed) {
                        logger.debug("User {} was not written back to the directory: {}", entry.getAppUserId(), failed.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException busy) {
            logger.debug("{} write-back(s) dropped: {}", copy.size(), busy.getMessage());
        }
    }

    /** The ids held, in one query; ids the directory does not hold are absent. */
    public Map<Long, Entry> find(Collection<Long> appUserIds) {
        Map<Long, Entry> found = new HashMap<>();
        List<Long> ids = appUserIds == null ? new ArrayList<>()
            : appUserIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return found;
        }
        this.jdbc.query(connection -> {
            PreparedStatement select = connection.prepareStatement("SELECT app_user_id, tenant_id, username, full_name, "
                + "status, updated_at FROM user_directory WHERE app_user_id = ANY (?)");
            select.setArray(1, connection.createArrayOf("bigint", ids.toArray()));
            return select;
        }, rs -> {
            Entry entry = new Entry(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant());
            found.put(entry.getAppUserId(), entry);
        });
        return found;
    }

    /** Stores the entry unless the directory already holds a state at least as new; answers whether it did. */
    public boolean apply(Entry entry) {
        return this.jdbc.update("INSERT INTO user_directory (app_user_id, tenant_id, username, full_name, status, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (app_user_id) DO UPDATE SET tenant_id = EXCLUDED.tenant_id, "
                + "username = EXCLUDED.username, full_name = EXCLUDED.full_name, status = EXCLUDED.status, "
                + "updated_at = EXCLUDED.updated_at WHERE user_directory.updated_at < EXCLUDED.updated_at",
            entry.getAppUserId(), entry.getTenantId(), entry.getUsername(), entry.getFullName(), entry.getStatus(),
            Timestamp.from(entry.getUpdatedAt())) > 0;
    }

    /** A page of the directory by id, for the reconciliation. */
    public List<Entry> page(long afterAppUserId, int limit) {
        return this.jdbc.query("SELECT app_user_id, tenant_id, username, full_name, status, updated_at FROM user_directory "
                + "WHERE app_user_id > ? ORDER BY app_user_id LIMIT ?",
            (rs, i) -> new Entry(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getTimestamp(6).toInstant()), afterAppUserId, limit);
    }

    /** The oldest state the directory holds: how stale it can be. Null when it is empty. */
    public Instant oldestUpdatedAt() {
        Timestamp oldest = this.jdbc.queryForObject("SELECT min(updated_at) FROM user_directory", Timestamp.class);
        return oldest == null ? null : oldest.toInstant();
    }
}
