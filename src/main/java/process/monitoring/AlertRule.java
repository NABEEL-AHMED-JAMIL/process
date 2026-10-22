package process.monitoring;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** MIG-355: one alert_rule row. A workspace's when tenantId is set, the platform's when it is null. */
public final class AlertRule {

    public Long id;
    public Long tenantId;
    public AlertKind kind;
    public String name;
    public boolean enabled = true;
    public String severity = "WARNING";
    /** One pipeline (source_job); null for every pipeline of the workspace. */
    public Long jobId;
    public int threshold = 1;
    public int windowMinutes = 60;
    public BigDecimal factor;
    public boolean bell = true;
    public boolean email;
    public boolean webhook;
    public Set<String> roles = new LinkedHashSet<>();
    /** F4: the teams whose members are told as well, by id. */
    public Set<Long> teams = new LinkedHashSet<>();
    public LocalTime quietStart;
    public LocalTime quietEnd;
    public String quietZone;
    /** Owner #6 (2026-10-19): told nobody until then -- alerts still open and resolve, and are told once it ends. */
    public Instant mutedUntil;
    public Long createdBy;
    public Instant created;
    public Long updatedBy;
    public Instant updated;

    static final String COLUMNS = "alert_rule_id, tenant_id, kind, name, enabled, severity, job_id, threshold, window_minutes, factor, "
        + "notify_bell, notify_email, notify_webhook, recipient_roles, recipient_teams, quiet_start, quiet_end, quiet_zone, created_by, "
        + "date_created, updated_by, date_updated, muted_until";

    static AlertRule of(ResultSet rs, int row) throws SQLException {
        AlertRule rule = new AlertRule();
        rule.id = rs.getLong("alert_rule_id");
        long tenant = rs.getLong("tenant_id");
        rule.tenantId = rs.wasNull() ? null : tenant;
        rule.kind = AlertKind.of(rs.getString("kind")).orElse(null);
        rule.name = rs.getString("name");
        rule.enabled = rs.getBoolean("enabled");
        rule.severity = rs.getString("severity");
        long job = rs.getLong("job_id");
        rule.jobId = rs.wasNull() ? null : job;
        rule.threshold = rs.getInt("threshold");
        rule.windowMinutes = rs.getInt("window_minutes");
        rule.factor = rs.getBigDecimal("factor");
        rule.bell = rs.getBoolean("notify_bell");
        rule.email = rs.getBoolean("notify_email");
        rule.webhook = rs.getBoolean("notify_webhook");
        rule.roles = words(rs.getString("recipient_roles"));
        rule.teams = words(rs.getString("recipient_teams")).stream().map(Long::valueOf).collect(Collectors.toCollection(LinkedHashSet::new));
        Time start = rs.getTime("quiet_start");
        Time end = rs.getTime("quiet_end");
        rule.quietStart = start == null ? null : start.toLocalTime();
        rule.quietEnd = end == null ? null : end.toLocalTime();
        rule.quietZone = rs.getString("quiet_zone");
        Timestamp muted = rs.getTimestamp("muted_until");
        rule.mutedUntil = muted == null ? null : muted.toInstant();
        long createdBy = rs.getLong("created_by");
        rule.createdBy = rs.wasNull() ? null : createdBy;
        Timestamp created = rs.getTimestamp("date_created");
        rule.created = created == null ? null : created.toInstant();
        long updatedBy = rs.getLong("updated_by");
        rule.updatedBy = rs.wasNull() ? null : updatedBy;
        Timestamp updated = rs.getTimestamp("date_updated");
        rule.updated = updated == null ? null : updated.toInstant();
        return rule;
    }

    static Set<String> words(String list) {
        Set<String> out = new LinkedHashSet<>();
        if (list == null) {
            return out;
        }
        Arrays.stream(list.split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(out::add);
        return out;
    }

    public boolean isPlatform() {
        return this.tenantId == null;
    }

    /** Whether the rule tells nobody at this instant (a mute that has not ended). */
    public boolean mutedAt(Instant now) {
        return this.mutedUntil != null && this.mutedUntil.isAfter(now);
    }

    public boolean critical() {
        return "CRITICAL".equals(this.severity);
    }

    /** The factor as a double: 2 when a slow-run rule has none. */
    public double factorOr(double fallback) {
        return this.factor == null ? fallback : this.factor.doubleValue();
    }

    /** What the console shows and edits. */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", this.id);
        out.put("scope", this.isPlatform() ? "platform" : "workspace");
        out.put("tenantId", this.tenantId);
        out.put("kind", this.kind == null ? null : this.kind.name());
        out.put("kindLabel", this.kind == null ? null : this.kind.label());
        out.put("name", this.name);
        out.put("enabled", this.enabled);
        out.put("severity", this.severity);
        out.put("jobId", this.jobId);
        out.put("threshold", this.threshold);
        out.put("windowMinutes", this.windowMinutes);
        out.put("factor", this.factor);
        Map<String, Object> channels = new LinkedHashMap<>();
        channels.put("bell", this.bell);
        channels.put("email", this.email);
        channels.put("webhook", this.webhook);
        out.put("channels", channels);
        out.put("roles", this.roles);
        out.put("teams", this.teams);
        Map<String, Object> quiet = null;
        if (this.quietStart != null) {
            quiet = new LinkedHashMap<>();
            quiet.put("start", this.quietStart.toString());
            quiet.put("end", this.quietEnd == null ? null : this.quietEnd.toString());
            quiet.put("zone", this.quietZone);
        }
        out.put("quietHours", quiet);
        out.put("mutedUntil", this.mutedAt(Instant.now()) ? this.mutedUntil.toString() : null);
        out.put("runbook", this.kind == null ? null : this.kind.runbook());
        out.put("whatToDo", this.kind == null ? null : this.kind.whatToDo());
        out.put("createdBy", this.createdBy);
        out.put("created", this.created == null ? null : this.created.toString());
        out.put("updatedBy", this.updatedBy);
        out.put("updated", this.updated == null ? null : this.updated.toString());
        return out;
    }
}
