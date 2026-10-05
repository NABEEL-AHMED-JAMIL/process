package process.forms;

import org.barco.platform.tenancy.RowSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A form's share links (MIG-278; form_share_link and form_share_policy, V198). A link's token is 32 random bytes shown
 * once; only its SHA-256 is kept, so a database read cannot open a form. Sharing is off for a workspace until an
 * administrator turns it on, and turning it off stops every link at once without revoking them.
 */
@Component
public class FormShareLinks {

    /** The role a share link's visitor acts in: nobody of the workspace (StorageFormInbox reads it). */
    public static final String LINK_ROLE = "FORM_LINK";
    static final int MAX_DAYS = 90;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public FormShareLinks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One link, without its token (which is never kept). */
    public static final class Link {
        public long linkId;
        public long formId;
        public long tenantId;
        public String label;
        public Instant expiresAt;
        public Integer maxSubmissions;
        public int usedCount;
        public boolean requireSignIn;
        public String status;
        public Long createdBy;
        public Instant createdAt;
        public Instant revokedAt;
        public Instant lastUsedAt;

        public boolean expired(Instant now) {
            return !this.expiresAt.isAfter(now);
        }

        public boolean usedUp() {
            return this.maxSubmissions != null && this.usedCount >= this.maxSubmissions;
        }
    }

    // ---- the workspace's switch ----------------------------------------------------------------------------------

    public boolean enabled(long tenantId) {
        List<Boolean> on = this.jdbc.queryForList("SELECT enabled FROM form_share_policy WHERE tenant_id = ?", Boolean.class, tenantId);
        return !on.isEmpty() && Boolean.TRUE.equals(on.get(0));
    }

    public void setEnabled(long tenantId, boolean enabled, Long by) {
        this.jdbc.update("INSERT INTO form_share_policy (tenant_id, enabled, updated_by, date_updated) VALUES (?, ?, ?, now()) "
            + "ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by, date_updated = now()",
            tenantId, enabled, by);
    }

    // ---- links ---------------------------------------------------------------------------------------------------

    /** A new link and its token (the only time the token exists outside the visitor's address bar). */
    public Map.Entry<String, Link> create(long tenantId, long formId, String label, int days, Integer maxSubmissions, boolean requireSignIn,
        Long by) {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Long id = this.jdbc.queryForObject("INSERT INTO form_share_link (form_id, tenant_id, token_hash, label, expires_at, max_submissions, "
            + "require_sign_in, created_by) VALUES (?, ?, ?, ?, now() + make_interval(days => ?), ?, ?, ?) RETURNING link_id", Long.class,
            formId, tenantId, hash(token), label, days, maxSubmissions, requireSignIn, by);
        return new AbstractMap.SimpleImmutableEntry<>(token, this.find(tenantId, id).get());
    }

    public List<Link> list(long tenantId, long formId) {
        return this.jdbc.query("SELECT * FROM form_share_link WHERE tenant_id = ? AND form_id = ? ORDER BY link_id DESC LIMIT 200",
            FormShareLinks::link, tenantId, formId);
    }

    public Optional<Link> find(long tenantId, long linkId) {
        return this.jdbc.query("SELECT * FROM form_share_link WHERE tenant_id = ? AND link_id = ?", FormShareLinks::link, tenantId, linkId)
            .stream().findFirst();
    }

    public boolean revoke(long tenantId, long linkId, Long by) {
        return this.jdbc.update("UPDATE form_share_link SET status = 'Revoked', revoked_by = ?, revoked_at = now() WHERE tenant_id = ? "
            + "AND link_id = ? AND status = 'Active'", by, tenantId, linkId) > 0;
    }

    /**
     * The link a token opens, in whichever workspace -- the one read that may cross workspaces, by the token's hash only
     * (a visitor names no workspace). Empty for an unknown or malformed token.
     */
    public Optional<Link> byToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        String hashed = hash(token);
        return RowSecurity.acrossTenants("a share link's visitor names no workspace: the link is found by its token's hash alone",
            () -> this.jdbc.query("SELECT * FROM form_share_link WHERE token_hash = ?", FormShareLinks::link, hashed).stream().findFirst());
    }

    /**
     * Takes one submission from the link, atomically: false when it is no longer Active, has expired, or is used up --
     * so two visitors racing for a one-use link cannot both submit.
     */
    public boolean take(long tenantId, long linkId) {
        return this.jdbc.update("UPDATE form_share_link SET used_count = used_count + 1, last_used_at = now() WHERE tenant_id = ? AND link_id = ? "
            + "AND status = 'Active' AND expires_at > now() AND (max_submissions IS NULL OR used_count < max_submissions)", tenantId, linkId) > 0;
    }

    /** The submission came through this link. */
    public void attach(long tenantId, long submissionId, long linkId) {
        this.jdbc.update("UPDATE form_submission SET share_link_id = ? WHERE tenant_id = ? AND submission_id = ?", linkId, tenantId, submissionId);
    }

    /** A file a link's visitor uploaded belongs to their ticket. */
    public void ticketUpload(long tenantId, long uploadId, String ticket) {
        this.jdbc.update("UPDATE form_upload SET share_ticket = ? WHERE tenant_id = ? AND upload_id = ?", ticket, tenantId, uploadId);
    }

    /** An unclaimed upload of this field made under this ticket. */
    public Optional<FormFields.Upload> openUpload(long tenantId, long formId, String fieldKey, String ticket, long uploadId) {
        return this.jdbc.query("SELECT upload_id, file_name, content_type, size_bytes, bucket, storage_key FROM form_upload WHERE tenant_id = ? "
            + "AND form_id = ? AND field_key = ? AND share_ticket = ? AND upload_id = ? AND submission_id IS NULL",
            (rs, i) -> new FormFields.Upload(rs.getLong("upload_id"), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("bucket"), rs.getString("storage_key")),
            tenantId, formId, fieldKey, ticket, uploadId).stream().findFirst();
    }

    /** A link as an administrator sees it. */
    public static Map<String, Object> view(Link l, Instant now) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("linkId", l.linkId);
        v.put("formId", l.formId);
        v.put("label", l.label);
        v.put("expiresAt", l.expiresAt.toString());
        v.put("maxSubmissions", l.maxSubmissions);
        v.put("usedCount", l.usedCount);
        v.put("requireSignIn", l.requireSignIn);
        v.put("status", "Revoked".equals(l.status) ? "Revoked" : l.expired(now) ? "Expired" : l.usedUp() ? "Used" : "Active");
        v.put("createdAt", l.createdAt.toString());
        v.put("lastUsedAt", l.lastUsedAt == null ? null : l.lastUsedAt.toString());
        return v;
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Link link(ResultSet rs, int row) throws SQLException {
        Link l = new Link();
        l.linkId = rs.getLong("link_id");
        l.formId = rs.getLong("form_id");
        l.tenantId = rs.getLong("tenant_id");
        l.label = rs.getString("label");
        l.expiresAt = rs.getTimestamp("expires_at").toInstant();
        l.maxSubmissions = rs.getObject("max_submissions", Integer.class);
        l.usedCount = rs.getInt("used_count");
        l.requireSignIn = rs.getBoolean("require_sign_in");
        l.status = rs.getString("status");
        l.createdBy = rs.getObject("created_by", Long.class);
        l.createdAt = rs.getTimestamp("date_created").toInstant();
        Timestamp revoked = rs.getTimestamp("revoked_at");
        l.revokedAt = revoked == null ? null : revoked.toInstant();
        Timestamp used = rs.getTimestamp("last_used_at");
        l.lastUsedAt = used == null ? null : used.toInstant();
        return l;
    }
}
