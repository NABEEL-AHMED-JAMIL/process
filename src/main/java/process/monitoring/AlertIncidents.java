package process.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MIG-355: alert_incident -- the alerts. One OPEN row per rule and subject (ux_alert_incident_open): the evaluator opens
 * it the first time a rule finds something, touches it (last_seen_at, occurrences) every time after, and resolves it when
 * the rule no longer finds it. Statements run as their caller's row security: the evaluator writes each workspace's as
 * that workspace and the platform's across workspaces; a person reads and resolves only their own scope's.
 */
@Component
public class AlertIncidents {

    /** Resolved alerts are kept this long, then removed; open ones are never removed. */
    public static final int KEEP_RESOLVED_DAYS = 30;

    static final String COLUMNS = "alert_incident_id, tenant_id, alert_rule_id, kind, subject, state, severity, title, detail, link, "
        + "job_id, job_queue_id, occurrences, first_seen_at, last_seen_at, notified_at, email_held, emailed_at, resolved_at, resolved_how, "
        + "resolved_by";

    private final JdbcTemplate jdbc;

    public AlertIncidents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One alert as stored. */
    public static final class Incident {
        public long id;
        public Long tenantId;
        public long ruleId;
        public String kind;
        public String subject;
        public String state;
        public String severity;
        public String title;
        public String detail;
        public String link;
        public Long jobId;
        public Long runId;
        public int occurrences;
        public Instant firstSeen;
        public Instant lastSeen;
        public Instant notified;
        public boolean emailHeld;
        public Instant emailed;
        public Instant resolved;
        public String resolvedHow;
        public Long resolvedBy;

        public boolean isOpen() {
            return "OPEN".equals(this.state);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", this.id);
            out.put("scope", this.tenantId == null ? "platform" : "workspace");
            out.put("tenantId", this.tenantId);
            out.put("ruleId", this.ruleId);
            out.put("kind", this.kind);
            AlertKind kind = AlertKind.of(this.kind).orElse(null);
            out.put("kindLabel", kind == null ? this.kind : kind.label());
            out.put("subject", this.subject);
            out.put("state", this.state);
            out.put("severity", this.severity);
            out.put("title", this.title);
            out.put("detail", this.detail);
            out.put("link", this.link);
            out.put("jobId", this.jobId);
            out.put("runId", this.runId);
            out.put("occurrences", this.occurrences);
            out.put("firstSeenAt", iso(this.firstSeen));
            out.put("lastSeenAt", iso(this.lastSeen));
            out.put("notifiedAt", iso(this.notified));
            out.put("emailHeld", this.emailHeld);
            out.put("emailedAt", iso(this.emailed));
            out.put("resolvedAt", iso(this.resolved));
            out.put("resolvedHow", this.resolvedHow);
            out.put("resolvedBy", this.resolvedBy);
            out.put("whatToDo", kind == null ? null : kind.whatToDo());
            out.put("runbook", kind == null ? null : kind.runbook());
            return out;
        }
    }

    static String iso(Instant at) {
        return at == null ? null : at.toString();
    }

    static Incident of(ResultSet rs, int row) throws SQLException {
        Incident incident = new Incident();
        incident.id = rs.getLong("alert_incident_id");
        long tenant = rs.getLong("tenant_id");
        incident.tenantId = rs.wasNull() ? null : tenant;
        incident.ruleId = rs.getLong("alert_rule_id");
        incident.kind = rs.getString("kind");
        incident.subject = rs.getString("subject");
        incident.state = rs.getString("state");
        incident.severity = rs.getString("severity");
        incident.title = rs.getString("title");
        incident.detail = rs.getString("detail");
        incident.link = rs.getString("link");
        long job = rs.getLong("job_id");
        incident.jobId = rs.wasNull() ? null : job;
        long run = rs.getLong("job_queue_id");
        incident.runId = rs.wasNull() ? null : run;
        incident.occurrences = rs.getInt("occurrences");
        incident.firstSeen = instant(rs.getTimestamp("first_seen_at"));
        incident.lastSeen = instant(rs.getTimestamp("last_seen_at"));
        incident.notified = instant(rs.getTimestamp("notified_at"));
        incident.emailHeld = rs.getBoolean("email_held");
        incident.emailed = instant(rs.getTimestamp("emailed_at"));
        incident.resolved = instant(rs.getTimestamp("resolved_at"));
        incident.resolvedHow = rs.getString("resolved_how");
        long by = rs.getLong("resolved_by");
        incident.resolvedBy = rs.wasNull() ? null : by;
        return incident;
    }

    private static Instant instant(Timestamp at) {
        return at == null ? null : at.toInstant();
    }

    /** The open alerts of these rules, by rule and then subject: one query for the evaluator's pass. */
    public Map<Long, Map<String, Incident>> openOf(Collection<Long> ruleIds) {
        Map<Long, Map<String, Incident>> out = new HashMap<>();
        if (ruleIds.isEmpty()) {
            return out;
        }
        String ids = ruleIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        for (Incident incident : this.jdbc.query("SELECT " + COLUMNS + " FROM alert_incident WHERE state = 'OPEN' AND alert_rule_id IN ("
            + ids + ")", AlertIncidents::of)) {
            out.computeIfAbsent(incident.ruleId, id -> new HashMap<>()).put(incident.subject, incident);
        }
        return out;
    }

    /**
     * Opens an alert for the rule and subject, or answers empty when one is already open (the unique index decides: a
     * second evaluator, or a race, never makes two).
     */
    public Optional<Long> open(AlertRule rule, Finding finding, Instant now) {
        List<Long> made = this.jdbc.queryForList("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title, detail, "
                + "link, job_id, job_queue_id, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (alert_rule_id, subject) WHERE state = 'OPEN' DO NOTHING RETURNING alert_incident_id", Long.class,
            rule.tenantId, rule.id, rule.kind.name(), cut(finding.subject, 160), finding.severity == null ? rule.severity : finding.severity,
            cut(finding.title, 300), cut(finding.detail, 2000), cut(finding.link, 500), finding.jobId, finding.runId, Timestamp.from(now),
            Timestamp.from(now));
        return made.stream().findFirst();
    }

    /** Still found: when, how often, and what it says now. */
    public void touch(long id, Finding finding, Instant now) {
        this.jdbc.update("UPDATE alert_incident SET last_seen_at = ?, occurrences = occurrences + 1, title = ?, detail = ?, link = ?, "
                + "job_queue_id = coalesce(?, job_queue_id) WHERE alert_incident_id = ? AND state = 'OPEN'", Timestamp.from(now),
            cut(finding.title, 300), cut(finding.detail, 2000), cut(finding.link, 500), finding.runId, id);
    }

    /** Closes it -- AUTO by the evaluator, MANUAL by a person; false when it was not open (or not the caller's). */
    public boolean resolve(long id, String how, Long by, Instant now) {
        return this.jdbc.update("UPDATE alert_incident SET state = 'RESOLVED', resolved_at = ?, resolved_how = ?, resolved_by = ? "
            + "WHERE alert_incident_id = ? AND state = 'OPEN'", Timestamp.from(now), how, by, id) == 1;
    }

    public void notified(long id, Instant now) {
        this.jdbc.update("UPDATE alert_incident SET notified_at = ? WHERE alert_incident_id = ?", Timestamp.from(now), id);
    }

    public void emailHeld(long id, boolean held) {
        this.jdbc.update("UPDATE alert_incident SET email_held = ? WHERE alert_incident_id = ?", held, id);
    }

    public void emailed(long id, Instant now) {
        this.jdbc.update("UPDATE alert_incident SET email_held = false, emailed_at = ? WHERE alert_incident_id = ?", Timestamp.from(now), id);
    }

    public Optional<Incident> find(long id) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM alert_incident WHERE alert_incident_id = ?", AlertIncidents::of, id)
            .stream().findFirst();
    }

    /**
     * The caller's alerts, newest first: a workspace's (tenantId) or the platform's (null). {@code state} OPEN, RESOLVED or
     * null for both; at most {@code limit}.
     */
    public List<Incident> list(Long tenantId, String state, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM alert_incident WHERE ");
        if (tenantId == null) {
            sql.append("tenant_id IS NULL");
        } else {
            sql.append("tenant_id = ?");
            args.add(tenantId);
        }
        if (state != null) {
            sql.append(" AND state = ?");
            args.add(state);
        }
        sql.append(" ORDER BY (state = 'OPEN') DESC, last_seen_at DESC LIMIT ").append(Math.max(1, Math.min(limit, 500)));
        return this.jdbc.query(sql.toString(), AlertIncidents::of, args.toArray());
    }

    /** Resolved alerts older than {@value #KEEP_RESOLVED_DAYS} days, removed; how many. */
    public int purge(Instant now) {
        return this.jdbc.update("DELETE FROM alert_incident WHERE state = 'RESOLVED' AND resolved_at < ?",
            Timestamp.from(now.minusSeconds(KEEP_RESOLVED_DAYS * 86_400L)));
    }

    static String cut(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
