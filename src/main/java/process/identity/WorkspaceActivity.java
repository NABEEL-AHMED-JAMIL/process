package process.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Owner #19 (2026-10-19): what Platform › Workspaces shows of each workspace's activity that Core knows -- its runs this
 * month (Chicago's month: the platform's business zone), how many of them failed, and its alerts open now. Two statements
 * for a page of workspaces, under InternalTenantFactsRestApi's across-workspaces read.
 */
@Component
public class WorkspaceActivity {

    static final ZoneId ZONE = ZoneId.of("America/Chicago");

    /** One workspace's figures. */
    public static final class Figures {
        public long runsThisMonth;
        public long failedThisMonth;
        public long openAlerts;
    }

    private final NamedParameterJdbcTemplate jdbc;

    public WorkspaceActivity(JdbcTemplate jdbc) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    /** The first instant of this month in the business zone. */
    static Instant monthStart(Instant now) {
        return LocalDate.ofInstant(now, ZONE).withDayOfMonth(1).atStartOfDay(ZONE).toInstant();
    }

    public Map<Long, Figures> of(Collection<Long> ids, Instant now) {
        Map<Long, Figures> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        MapSqlParameterSource args = new MapSqlParameterSource("ids", ids).addValue("from", Timestamp.from(monthStart(now)));
        this.jdbc.query("SELECT tenant_id, count(*) AS runs, count(*) FILTER (WHERE job_status IN ('Failed', 'Interrupt')) AS failed "
            + "FROM job_queue WHERE tenant_id IN (:ids) AND date_created >= :from GROUP BY tenant_id", args, rs -> {
                Figures f = out.computeIfAbsent(rs.getLong("tenant_id"), k -> new Figures());
                f.runsThisMonth = rs.getLong("runs");
                f.failedThisMonth = rs.getLong("failed");
            });
        this.jdbc.query("SELECT tenant_id, count(*) AS open FROM alert_incident WHERE tenant_id IN (:ids) AND state = 'OPEN' "
            + "GROUP BY tenant_id", args, rs -> {
                out.computeIfAbsent(rs.getLong("tenant_id"), k -> new Figures()).openAlerts = rs.getLong("open");
            });
        return out;
    }
}
