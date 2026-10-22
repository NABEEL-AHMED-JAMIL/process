package process.monitoring;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.barco.platform.tenancy.RowSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * MIG-355: alert_rule -- read, checked and written. Every read and write runs as the caller (row security): a workspace
 * administrator reaches only their workspace's rules, a platform administrator (an all-tenants session) the platform's,
 * whose tenant_id is NULL. The evaluator reads every enabled rule across workspaces ({@link #enabledEverywhere}).
 *
 * <p>The checks, each refused with a sentence (a 400): the kind is one this scope may watch; a name of 1 to 120
 * characters; each kind's own numbers in range; a pipeline that is the workspace's; recipients that are roles of the
 * workspace (TENANT_ADMIN, TENANT_USER) or teams (team ids, at most 20); someone to tell when the bell or mail
 * is on; the webhook only for a workspace's rule; quiet hours both ends or neither, in a real time zone; at most
 * {@value #MAX_RULES} rules a workspace (and as many for the platform).
 */
@Component
public class AlertRules {

    public static final int MAX_RULES = 50;
    public static final int MAX_TEAMS = 20;
    public static final String DEFAULT_ZONE = "America/Chicago";
    static final Set<String> WORKSPACE_ROLES = new HashSet<>(Arrays.asList("TENANT_ADMIN", "TENANT_USER"));

    private final JdbcTemplate jdbc;
    /** ZT-M15: whose teams a rule may tell; absent in a test that builds the rules without Identity. */
    private IdentityPort identity;

    public AlertRules(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Autowired(required = false)
    public void useIdentity(IdentityPort identity) {
        this.identity = identity;
    }

    /** A request refused as a whole: its status and sentence. */
    public static final class Refused extends RuntimeException {
        private final HttpStatus status;

        public Refused(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus getStatus() {
            return this.status;
        }
    }

    /** The caller's rules: a workspace's (tenantId) or, for null, the platform's. */
    public List<AlertRule> list(Long tenantId) {
        return tenantId == null
            ? this.jdbc.query("SELECT " + AlertRule.COLUMNS + " FROM alert_rule WHERE tenant_id IS NULL ORDER BY alert_rule_id", AlertRule::of)
            : this.jdbc.query("SELECT " + AlertRule.COLUMNS + " FROM alert_rule WHERE tenant_id = ? ORDER BY alert_rule_id", AlertRule::of,
                tenantId);
    }

    public Optional<AlertRule> find(long id, Long tenantId) {
        List<AlertRule> found = tenantId == null
            ? this.jdbc.query("SELECT " + AlertRule.COLUMNS + " FROM alert_rule WHERE alert_rule_id = ? AND tenant_id IS NULL", AlertRule::of, id)
            : this.jdbc.query("SELECT " + AlertRule.COLUMNS + " FROM alert_rule WHERE alert_rule_id = ? AND tenant_id = ?", AlertRule::of, id,
                tenantId);
        return found.stream().findFirst();
    }

    /** Every enabled rule of every workspace and the platform, for the evaluator: one query. */
    public List<AlertRule> enabledEverywhere() {
        return RowSecurity.acrossTenants("the alert evaluator (MIG-355) reads every workspace's enabled alert rules and the platform's; "
            + "each workspace's alerts are then written as that workspace",
            () -> this.jdbc.query("SELECT " + AlertRule.COLUMNS + " FROM alert_rule WHERE enabled ORDER BY tenant_id NULLS FIRST, alert_rule_id",
                AlertRule::of));
    }

    /**
     * Checks the rule and writes it for this scope (tenantId null: the platform's): a new one when it has no id, else the
     * existing one, which must be this scope's. Answers it as stored.
     */
    public AlertRule save(AlertRule in, Long tenantId, Long by) {
        AlertRule rule = this.checked(in, tenantId);
        if (rule.id == null) {
            Integer count = tenantId == null
                ? this.jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id IS NULL", Integer.class)
                : this.jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ?", Integer.class, tenantId);
            if (count != null && count >= MAX_RULES) {
                throw new Refused(HttpStatus.BAD_REQUEST, "At most " + MAX_RULES + " alert rules; remove one first.");
            }
            KeyHolder keys = new GeneratedKeyHolder();
            this.jdbc.update(connection -> {
                PreparedStatement insert = connection.prepareStatement("INSERT INTO alert_rule (tenant_id, kind, name, enabled, severity, job_id, "
                    + "threshold, window_minutes, factor, notify_bell, notify_email, notify_webhook, recipient_roles, recipient_teams, "
                    + "quiet_start, quiet_end, quiet_zone, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new String[]{"alert_rule_id"});
                bind(insert, rule, tenantId);
                setLong(insert, 18, by);
                return insert;
            }, keys);
            rule.id = keys.getKey() == null ? null : keys.getKey().longValue();
        } else {
            if (!this.find(rule.id, tenantId).isPresent()) {
                throw new Refused(HttpStatus.NOT_FOUND, "No such alert rule.");
            }
            this.jdbc.update(connection -> {
                PreparedStatement update = connection.prepareStatement("UPDATE alert_rule SET tenant_id = ?, kind = ?, name = ?, enabled = ?, "
                    + "severity = ?, job_id = ?, threshold = ?, window_minutes = ?, factor = ?, notify_bell = ?, notify_email = ?, "
                    + "notify_webhook = ?, recipient_roles = ?, recipient_teams = ?, quiet_start = ?, quiet_end = ?, quiet_zone = ?, "
                    + "updated_by = ?, date_updated = now() WHERE alert_rule_id = ?");
                bind(update, rule, tenantId);
                setLong(update, 18, by);
                update.setLong(19, rule.id);
                return update;
            });
        }
        return this.find(rule.id, tenantId).orElseThrow(() -> new Refused(HttpStatus.NOT_FOUND, "No such alert rule."));
    }

    /** The longest a rule may be muted: a week. */
    public static final int MAX_MUTE_MINUTES = 7 * 24 * 60;

    /**
     * Owner #6: mutes the rule for so many minutes from now (0 ends a mute). False when it is not this scope's.
     */
    public boolean mute(long id, Long tenantId, int minutes, Long by) {
        if (minutes < 0 || minutes > MAX_MUTE_MINUTES) {
            throw new Refused(HttpStatus.BAD_REQUEST, "A rule is muted for 1 minute to 7 days (10080 minutes), or 0 to end the mute.");
        }
        Timestamp until = minutes == 0 ? null : Timestamp.from(Instant.now().plusSeconds(minutes * 60L));
        return (tenantId == null
            ? this.jdbc.update("UPDATE alert_rule SET muted_until = ?, updated_by = ?, date_updated = now() WHERE alert_rule_id = ? "
                + "AND tenant_id IS NULL", until, by, id)
            : this.jdbc.update("UPDATE alert_rule SET muted_until = ?, updated_by = ?, date_updated = now() WHERE alert_rule_id = ? "
                + "AND tenant_id = ?", until, by, id, tenantId)) == 1;
    }

    /**
     * Owner #6: per rule of this scope, when it last fired (its newest alert's first sighting) and how many of its alerts are
     * open now: {ruleId: [lastFiredAt or null, open]}.
     */
    public Map<Long, Object[]> firing(Long tenantId) {
        Map<Long, Object[]> out = new HashMap<>();
        String sql = "SELECT alert_rule_id, max(first_seen_at), count(*) FILTER (WHERE state = 'OPEN') FROM alert_incident WHERE "
            + (tenantId == null ? "tenant_id IS NULL" : "tenant_id = ?") + " GROUP BY alert_rule_id";
        RowCallbackHandler row = rs -> {
            Timestamp last = rs.getTimestamp(2);
            out.put(rs.getLong(1), new Object[]{last == null ? null : last.toInstant().toString(), rs.getLong(3)});
        };
        if (tenantId == null) {
            this.jdbc.query(sql, row);
        } else {
            this.jdbc.query(sql, row, tenantId);
        }
        return out;
    }

    /** Removes the rule, and its alerts with it. False when it is not this scope's. */
    public boolean delete(long id, Long tenantId) {
        return (tenantId == null
            ? this.jdbc.update("DELETE FROM alert_rule WHERE alert_rule_id = ? AND tenant_id IS NULL", id)
            : this.jdbc.update("DELETE FROM alert_rule WHERE alert_rule_id = ? AND tenant_id = ?", id, tenantId)) == 1;
    }

    private static void bind(PreparedStatement statement, AlertRule rule, Long tenantId) throws SQLException {
        setLong(statement, 1, tenantId);
        statement.setString(2, rule.kind.name());
        statement.setString(3, rule.name);
        statement.setBoolean(4, rule.enabled);
        statement.setString(5, rule.severity);
        setLong(statement, 6, rule.jobId);
        statement.setInt(7, rule.threshold);
        statement.setInt(8, rule.windowMinutes);
        if (rule.factor == null) {
            statement.setNull(9, Types.NUMERIC);
        } else {
            statement.setBigDecimal(9, rule.factor);
        }
        statement.setBoolean(10, rule.bell);
        statement.setBoolean(11, rule.email);
        statement.setBoolean(12, rule.webhook);
        statement.setString(13, String.join(",", rule.roles));
        statement.setString(14, rule.teams.isEmpty() ? null : rule.teams.stream().map(String::valueOf).collect(Collectors.joining(",")));
        statement.setTime(15, rule.quietStart == null ? null : Time.valueOf(rule.quietStart));
        statement.setTime(16, rule.quietEnd == null ? null : Time.valueOf(rule.quietEnd));
        statement.setString(17, rule.quietZone);
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    /** The rule as it will be stored, or the sentence that refuses it. */
    AlertRule checked(AlertRule in, Long tenantId) {
        if (in == null || in.kind == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Say what the rule watches (kind).");
        }
        boolean platform = tenantId == null;
        if (in.kind.isPlatform() != platform) {
            throw new Refused(HttpStatus.BAD_REQUEST, platform ? "The platform's rules watch services, memory, Kafka, disk and billing; "
                + in.kind.name() + " is a workspace's." : in.kind.name() + " is the platform's to watch, not a workspace's.");
        }
        AlertRule rule = new AlertRule();
        rule.id = in.id;
        rule.tenantId = tenantId;
        rule.kind = in.kind;
        String name = in.name == null || in.name.trim().isEmpty() ? in.kind.label() : in.name.trim();
        if (name.length() > 120) {
            throw new Refused(HttpStatus.BAD_REQUEST, "A rule's name is at most 120 characters.");
        }
        rule.name = name;
        rule.enabled = in.enabled;
        String severity = in.severity == null ? "WARNING" : in.severity.trim().toUpperCase(Locale.ROOT);
        if (!"WARNING".equals(severity) && !"CRITICAL".equals(severity)) {
            throw new Refused(HttpStatus.BAD_REQUEST, "severity is WARNING or CRITICAL.");
        }
        rule.severity = severity;
        rule.threshold = in.threshold;
        rule.windowMinutes = in.windowMinutes;
        rule.factor = in.factor;
        this.numbers(rule);
        if (in.jobId != null) {
            if (platform) {
                throw new Refused(HttpStatus.BAD_REQUEST, "A platform rule watches no pipeline.");
            }
            Integer mine = this.jdbc.queryForObject("SELECT count(*) FROM source_job WHERE job_id = ? AND tenant_id = ? AND job_status <> 'Delete'",
                Integer.class, in.jobId, tenantId);
            if (mine == null || mine == 0) {
                throw new Refused(HttpStatus.BAD_REQUEST, "No such pipeline in this workspace (jobId " + in.jobId + ").");
            }
            rule.jobId = in.jobId;
        }
        rule.bell = in.bell;
        rule.email = in.email;
        rule.webhook = in.webhook;
        if (platform) {
            if (in.webhook) {
                throw new Refused(HttpStatus.BAD_REQUEST, "The platform's alerts have no workspace and go to no webhook; use the bell and mail.");
            }
            rule.roles = new LinkedHashSet<>(Arrays.asList("PLATFORM_ADMIN"));
            if (in.teams != null && !in.teams.isEmpty()) {
                throw new Refused(HttpStatus.BAD_REQUEST, "A platform rule tells every platform administrator; it takes no teams.");
            }
        } else {
            Set<String> roles = new LinkedHashSet<>();
            for (String role : in.roles == null ? Collections.<String>emptySet() : in.roles) {
                String upper = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
                if (!WORKSPACE_ROLES.contains(upper)) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "roles are the workspace's: tenant administrators or tenant users; " + role + " is not one.");
                }
                roles.add(upper);
            }
            rule.roles = roles;
            Set<Long> teams = new LinkedHashSet<>(in.teams == null ? Collections.<Long>emptySet() : in.teams);
            teams.remove(null);
            if (teams.size() > MAX_TEAMS) {
                throw new Refused(HttpStatus.BAD_REQUEST, "At most " + MAX_TEAMS + " teams.");
            }
            if (teams.stream().anyMatch(id -> id <= 0)) {
                throw new Refused(HttpStatus.BAD_REQUEST, "teams are team ids.");
            }
            // ZT-M15: only this workspace's active teams; another's would be told nothing (and named nothing here).
            for (Long team : this.identity == null ? Collections.<Long>emptySet() : teams) {
                Boolean ours = this.identity.isActiveTeamOf(tenantId, team);
                if (ours == null) {
                    throw new Refused(HttpStatus.SERVICE_UNAVAILABLE, "Identity could not say whether team " + team + " is this workspace's. Save again in a moment.");
                }
                if (!ours) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "That team is not in this workspace. (team " + team + ")");
                }
            }
            rule.teams = teams;
            if ((rule.bell || rule.email) && roles.isEmpty() && teams.isEmpty()) {
                throw new Refused(HttpStatus.BAD_REQUEST, "Name who hears of it: a role (tenant administrators, tenant users) or a team.");
            }
        }
        if (!rule.bell && !rule.email && !rule.webhook) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Turn on at least one way to hear of it: the bell, mail or a webhook.");
        }
        if ((in.quietStart == null) != (in.quietEnd == null)) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Quiet hours need a start and an end, or neither.");
        }
        if (in.quietStart != null) {
            if (in.quietStart.equals(in.quietEnd)) {
                throw new Refused(HttpStatus.BAD_REQUEST, "Quiet hours start and end at different times.");
            }
            String zone = in.quietZone == null || in.quietZone.trim().isEmpty() ? DEFAULT_ZONE : in.quietZone.trim();
            try {
                ZoneId.of(zone);
            } catch (DateTimeException unknown) {
                throw new Refused(HttpStatus.BAD_REQUEST, "No such time zone: " + zone + ".");
            }
            rule.quietStart = in.quietStart;
            rule.quietEnd = in.quietEnd;
            rule.quietZone = zone;
        }
        return rule;
    }

    /** Each kind's numbers: what it takes, the range, and the defaults it falls back to. */
    private void numbers(AlertRule rule) {
        switch (rule.kind) {
            case RUN_FAILED:
                range("threshold (failed runs)", rule.threshold, 1, 1000);
                range("windowMinutes", rule.windowMinutes, 1, 1440);
                rule.factor = null;
                break;
            case SCHEDULE_MISSED:
                rule.threshold = 1;
                range("windowMinutes", rule.windowMinutes, 5, 1440);
                rule.factor = null;
                break;
            case RUN_SLOW:
                rule.threshold = 1;
                range("windowMinutes", rule.windowMinutes, 5, 1440);
                BigDecimal factor = rule.factor == null ? BigDecimal.valueOf(2) : rule.factor;
                if (factor.compareTo(new BigDecimal("1.2")) < 0 || factor.compareTo(BigDecimal.valueOf(20)) > 0) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "factor is 1.2 to 20 times the pipeline's usual (p95) duration.");
                }
                rule.factor = factor;
                break;
            case REVIEW_WAITING:
                rule.threshold = 1;
                range("windowMinutes (how long a review may wait)", rule.windowMinutes, 5, 10080);
                rule.factor = null;
                break;
            case SERVICE_UNHEALTHY:
                range("threshold (minutes down)", rule.threshold, 1, 60);
                rule.factor = null;
                break;
            case SERVICE_RESTARTED:
                rule.threshold = 1;
                range("windowMinutes", rule.windowMinutes, 5, 1440);
                rule.factor = null;
                break;
            case MEMORY_HIGH:
                range("threshold (percent of the heap)", rule.threshold, 50, 99);
                rule.factor = null;
                break;
            case KAFKA_LAG:
                range("threshold (messages behind)", rule.threshold, 1, 1_000_000);
                rule.factor = null;
                break;
            case DISK_LOW:
                range("threshold (percent free)", rule.threshold, 1, 50);
                rule.factor = null;
                break;
            case BILLING_MISMATCH:
            default:
                rule.threshold = 1;
                rule.factor = null;
                break;
        }
        if (rule.windowMinutes < 1 || rule.windowMinutes > 10080) {
            rule.windowMinutes = 60;
        }
    }

    private static void range(String what, int value, int low, int high) {
        if (value < low || value > high) {
            throw new Refused(HttpStatus.BAD_REQUEST, what + " is " + low + " to " + high + ".");
        }
    }

    /** "22:00" or "22:00:00" as a time, or a refusal. */
    public static LocalTime time(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(String.valueOf(value).trim());
        } catch (DateTimeParseException unreadable) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Quiet hours are times of day, e.g. 22:00.");
        }
    }

    /** The workspaces the rules belong to, without the platform's. */
    static Set<Long> workspacesOf(Collection<AlertRule> rules) {
        return rules.stream().filter(r -> r.tenantId != null).map(r -> r.tenantId).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A rule from the console's body: {id, kind, name, enabled, severity, jobId, threshold, windowMinutes, factor, channels, roles, teams, quietHours}. */
    @SuppressWarnings("unchecked")
    public static AlertRule fromBody(Map<String, Object> body) {
        if (body == null) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Send the rule.");
        }
        AlertRule rule = new AlertRule();
        rule.id = asLong(body.get("id"));
        rule.kind = AlertKind.of(body.get("kind") == null ? null : String.valueOf(body.get("kind")))
            .orElseThrow(() -> new Refused(HttpStatus.BAD_REQUEST, "kind is one of " + Arrays.toString(AlertKind.values()) + "."));
        rule.name = body.get("name") == null ? null : String.valueOf(body.get("name"));
        rule.enabled = !Boolean.FALSE.equals(body.get("enabled"));
        rule.severity = body.get("severity") == null ? null : String.valueOf(body.get("severity"));
        rule.jobId = asLong(body.get("jobId"));
        Long threshold = asLong(body.get("threshold"));
        rule.threshold = threshold == null ? defaultThreshold(rule.kind) : threshold.intValue();
        Long window = asLong(body.get("windowMinutes"));
        rule.windowMinutes = window == null ? defaultWindow(rule.kind) : window.intValue();
        Object factor = body.get("factor");
        try {
            rule.factor = factor == null || String.valueOf(factor).trim().isEmpty() ? null : new BigDecimal(String.valueOf(factor).trim());
        } catch (NumberFormatException unreadable) {
            throw new Refused(HttpStatus.BAD_REQUEST, "factor is a number, e.g. 2.");
        }
        Object channels = body.get("channels");
        Map<String, Object> ch = channels instanceof Map ? (Map<String, Object>) channels : Collections.emptyMap();
        rule.bell = ch.isEmpty() || Boolean.TRUE.equals(ch.get("bell"));
        rule.email = Boolean.TRUE.equals(ch.get("email"));
        rule.webhook = Boolean.TRUE.equals(ch.get("webhook"));
        Object roles = body.get("roles");
        rule.roles = new LinkedHashSet<>();
        if (roles instanceof Collection) {
            for (Object role : (Collection<Object>) roles) {
                rule.roles.add(role == null ? null : String.valueOf(role));
            }
        } else if (roles == null) {
            rule.roles.add("TENANT_ADMIN");
        }
        Object teams = body.get("teams");
        rule.teams = new LinkedHashSet<>();
        if (teams instanceof Collection) {
            for (Object team : (Collection<Object>) teams) {
                Long id = asLong(team);
                if (id == null) {
                    throw new Refused(HttpStatus.BAD_REQUEST, "teams are team ids.");
                }
                rule.teams.add(id);
            }
        }
        Object quiet = body.get("quietHours");
        if (quiet instanceof Map) {
            Map<String, Object> q = (Map<String, Object>) quiet;
            rule.quietStart = time(q.get("start"));
            rule.quietEnd = time(q.get("end"));
            rule.quietZone = q.get("zone") == null ? null : String.valueOf(q.get("zone"));
        }
        return rule;
    }

    static int defaultThreshold(AlertKind kind) {
        switch (kind) {
            case SERVICE_UNHEALTHY: return 2;
            case MEMORY_HIGH: return 85;
            case KAFKA_LAG: return 1000;
            case DISK_LOW: return 10;
            default: return 1;
        }
    }

    static int defaultWindow(AlertKind kind) {
        switch (kind) {
            case RUN_FAILED: return 60;
            case SCHEDULE_MISSED: return 60;
            case RUN_SLOW: return 60;
            case REVIEW_WAITING: return 240;
            case SERVICE_RESTARTED: return 30;
            default: return 10;
        }
    }

    private static Long asLong(Object value) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException unreadable) {
            throw new Refused(HttpStatus.BAD_REQUEST, "Expected a whole number, got " + value + ".");
        }
    }
}
