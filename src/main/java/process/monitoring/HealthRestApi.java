package process.monitoring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * Platform › Service health (MIG-355; slimmed by owner #21, 2026-10-19), platform administrators only -- GET
 * reliability.json/health: each service's status, the version it runs (ServiceVersions, written by scripts/deploy.sh),
 * when its JVM started, its restarts in 24 hours (planned or not) and its error rate (5xx a minute against requests a
 * minute, from the latest sample); the platform probes' latest readings (Kafka lag per group, billing reconciliation --
 * the disk probe is read by the disk rule, not shown); and the platform's alerts of the last 24 hours, open first.
 *
 * <p>No history of heap, CPU or requests: on AWS that is CloudWatch's (Container Insights), and the page does not
 * duplicate it. The samples keep the heap figures only because the memory rule reads them (HealthCollector.recent).
 *
 * <p>The planned deploys of the 24 hours (PlannedDeploys, announced by scripts/deploy.sh) as {@code deploys}, and per
 * service how many of its restarts and minutes not UP fell inside one ({@code plannedRestarts24h},
 * {@code plannedMinutesNotUp24h}): what the restart and unhealthy rules did not alert on.
 */
@RestController
@RequestMapping("/reliability.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class HealthRestApi {

    private final JdbcTemplate jdbc;
    private final HealthCollector collector;
    private final AlertIncidents incidents;
    private final PlannedDeploys planned;
    private final ServiceVersions versions;

    public HealthRestApi(JdbcTemplate jdbc, HealthCollector collector, AlertIncidents incidents, PlannedDeploys planned,
        ServiceVersions versions) {
        this.jdbc = jdbc;
        this.collector = collector;
        this.incidents = incidents;
        this.planned = planned;
        this.versions = versions;
    }

    @GetMapping("/health")
    public ResponseEntity<ResponseDto> health() {
        Instant now = Instant.now();
        Timestamp dayAgo = Timestamp.from(now.minusSeconds(86_400));
        Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        for (String name : this.collector.serviceNames()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("service", name);
            empty.put("status", "UNKNOWN");
            services.put(name, empty);
        }
        this.jdbc.query("SELECT DISTINCT ON (service) service, sampled_at, status, source, started_at, requests_per_min, errors_per_min "
            + "FROM service_health_sample WHERE sampled_at >= ? ORDER BY service, sampled_at DESC", rs -> {
                Map<String, Object> s = services.computeIfAbsent(rs.getString("service"), k -> new LinkedHashMap<>());
                s.put("service", rs.getString("service"));
                s.put("status", rs.getString("status"));
                s.put("source", rs.getString("source"));
                s.put("sampledAt", iso(rs, "sampled_at"));
                s.put("startedAt", iso(rs, "started_at"));
                BigDecimal rpm = rs.getBigDecimal("requests_per_min");
                BigDecimal epm = rs.getBigDecimal("errors_per_min");
                s.put("requestsPerMin", rpm);
                s.put("errorsPerMin", epm);
                s.put("errorPct", errorPct(rpm, epm));
            }, Timestamp.from(now.minusSeconds(600)));
        this.jdbc.query("SELECT service, greatest(count(DISTINCT started_at) - 1, 0) AS restarts, count(*) FILTER (WHERE status <> 'UP') AS down "
            + "FROM service_health_sample WHERE sampled_at >= ? GROUP BY service", rs -> {
                Map<String, Object> s = services.get(rs.getString("service"));
                if (s != null) {
                    s.put("restarts24h", rs.getLong("restarts"));
                    s.put("minutesNotUp24h", rs.getLong("down"));
                }
            }, dayAgo);
        Map<String, ServiceVersions.Version> running = this.versions.all();
        services.forEach((name, s) -> {
            ServiceVersions.Version v = running.get(name);
            s.put("version", v == null ? null : v.version);
            s.put("branch", v == null ? null : v.branch);
            s.put("deployedAt", v == null || v.deployedAt == null ? null : v.deployedAt.toString());
        });
        this.markPlanned(services, now, dayAgo);
        List<Map<String, Object>> probes = new ArrayList<>();
        this.collector.probes().values().forEach(p -> {
            if (p.name.startsWith(HealthCollector.DISK_PREFIX)) {
                return;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("probe", p.name);
            one.put("readAt", p.at == null ? null : p.at.toString());
            one.put("ok", p.ok);
            one.put("reading", p.reading);
            one.put("detail", p.detail);
            probes.add(one);
        });
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (AlertIncidents.Incident incident : this.incidents.list(null, null, 50)) {
            if (incident.isOpen() || (incident.lastSeen != null && incident.lastSeen.isAfter(now.minusSeconds(86_400)))) {
                alerts.add(incident.toMap());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("asOf", now.toString());
        data.put("services", new ArrayList<>(services.values()));
        data.put("probes", probes);
        data.put("incidents", alerts);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Platform health.", data), HttpStatus.OK);
    }

    /** 5xx answers as a share of requests, one place: null when nothing was asked. */
    static BigDecimal errorPct(BigDecimal requestsPerMin, BigDecimal errorsPerMin) {
        return requestsPerMin == null || errorsPerMin == null || requestsPerMin.signum() == 0 ? null
            : errorsPerMin.multiply(BigDecimal.valueOf(100)).divide(requestsPerMin, 1, RoundingMode.HALF_UP);
    }

    /**
     * The 24 hours' planned deploys, newest first; and on each service that had one, its restarts and minutes not UP that
     * fell inside one (zero for the others).
     */
    private void markPlanned(Map<String, Map<String, Object>> services, Instant now, Timestamp dayAgo) {
        Map<String, List<DeployWindow>> byService = new LinkedHashMap<>();
        for (DeployWindow window : this.planned.recent(now)) {
            if (window.closes().isBefore(dayAgo.toInstant())) {
                continue;
            }
            byService.computeIfAbsent(window.service, k -> new ArrayList<>()).add(window);
        }
        services.forEach((name, s) -> {
            s.put("plannedRestarts24h", 0L);
            s.put("plannedMinutesNotUp24h", 0L);
            // Owner 2026-10-19: no list of deploys on the page; each service says its own -- how many in 24 hours, and the
            // newest one's state and outcome, so a deploy that failed or never closed its window is said on its row.
            List<DeployWindow> mine = byService.getOrDefault(name, List.of());
            s.put("deploys24h", (long) mine.size());
            s.put("lastDeploy", mine.isEmpty() ? null : newest(mine).toMap(now));
        });
        if (byService.isEmpty()) {
            return;
        }
        this.jdbc.query("SELECT DISTINCT service, started_at FROM service_health_sample WHERE sampled_at >= ? AND started_at >= ?", rs -> {
            Map<String, Object> s = services.get(rs.getString(1));
            if (s != null && DeployWindow.anyCovers(byService.get(rs.getString(1)), rs.getTimestamp(2).toInstant())) {
                s.put("plannedRestarts24h", (Long) s.get("plannedRestarts24h") + 1);
            }
        }, dayAgo, dayAgo);
        this.jdbc.query("SELECT service, sampled_at FROM service_health_sample WHERE sampled_at >= ? AND status <> 'UP'", rs -> {
            Map<String, Object> s = services.get(rs.getString(1));
            if (s != null && DeployWindow.anyCovers(byService.get(rs.getString(1)), rs.getTimestamp(2).toInstant())) {
                s.put("plannedMinutesNotUp24h", (Long) s.get("plannedMinutesNotUp24h") + 1);
            }
        }, dayAgo);
    }

    /** The window that started last (recent() is newest first, but the row should not depend on that). */
    static DeployWindow newest(List<DeployWindow> windows) {
        DeployWindow best = windows.get(0);
        for (DeployWindow w : windows) {
            if (w.start.isAfter(best.start)) {
                best = w;
            }
        }
        return best;
    }

    private static String iso(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant().toString();
    }

}
