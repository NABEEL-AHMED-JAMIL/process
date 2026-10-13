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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * MIG-355: Platform › Reliability's health view, platform administrators only -- GET reliability.json/health?bucketMinutes=:
 * each service's latest sample (status, heap, CPU, threads, requests and errors a minute, mean latency, when its JVM
 * started) with its restarts in 24 hours and its 24 hours in buckets (default 15 minutes, 5 to 60) as compact arrays;
 * the platform probes' latest readings (Kafka lag per group, disk, billing reconciliation); and the platform's alerts of
 * the last 24 hours, open first. Three statements over service_health_sample (at most a day of minutes), one over
 * platform_probe, one over alert_incident.
 *
 * <p>The planned deploys of the 24 hours (PlannedDeploys, announced by scripts/deploy.sh) as {@code deploys}, and per
 * service how many of its restarts and minutes not UP fell inside one ({@code plannedRestarts24h},
 * {@code plannedMinutesNotUp24h}): what the restart and unhealthy rules did not alert on. Two more statements over the
 * samples, only when there was a deploy.
 */
@RestController
@RequestMapping("/reliability.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class HealthRestApi {

    private final JdbcTemplate jdbc;
    private final HealthCollector collector;
    private final AlertIncidents incidents;
    private final PlannedDeploys planned;

    public HealthRestApi(JdbcTemplate jdbc, HealthCollector collector, AlertIncidents incidents, PlannedDeploys planned) {
        this.jdbc = jdbc;
        this.collector = collector;
        this.incidents = incidents;
        this.planned = planned;
    }

    @GetMapping("/health")
    public ResponseEntity<ResponseDto> health(@RequestParam(value = "bucketMinutes", required = false) Integer bucketMinutes) {
        int bucket = bucketMinutes == null ? 15 : bucketMinutes;
        if (bucket < 5 || bucket > 60) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "bucketMinutes is 5 to 60."), HttpStatus.BAD_REQUEST);
        }
        Instant now = Instant.now();
        Timestamp dayAgo = Timestamp.from(now.minusSeconds(86_400));
        Map<String, Map<String, Object>> services = new LinkedHashMap<>();
        for (String name : this.collector.serviceNames()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("service", name);
            empty.put("status", "UNKNOWN");
            services.put(name, empty);
        }
        this.jdbc.query("SELECT DISTINCT ON (service) service, sampled_at, status, source, response_ms, heap_used_mb, heap_max_mb, "
            + "heap_after_gc_pct, cpu_pct, threads, started_at, requests_per_min, errors_per_min, mean_ms FROM service_health_sample "
            + "WHERE sampled_at >= ? ORDER BY service, sampled_at DESC", rs -> {
                Map<String, Object> s = services.computeIfAbsent(rs.getString("service"), k -> new LinkedHashMap<>());
                s.put("service", rs.getString("service"));
                s.put("status", rs.getString("status"));
                s.put("source", rs.getString("source"));
                s.put("sampledAt", iso(rs, "sampled_at"));
                s.put("responseMs", integer(rs, "response_ms"));
                s.put("heapUsedMb", integer(rs, "heap_used_mb"));
                s.put("heapMaxMb", integer(rs, "heap_max_mb"));
                s.put("heapAfterGcPct", number(rs, "heap_after_gc_pct"));
                s.put("cpuPct", number(rs, "cpu_pct"));
                s.put("threads", integer(rs, "threads"));
                s.put("startedAt", iso(rs, "started_at"));
                BigDecimal rpm = rs.getBigDecimal("requests_per_min");
                BigDecimal epm = rs.getBigDecimal("errors_per_min");
                s.put("requestsPerMin", rpm);
                s.put("errorsPerMin", epm);
                s.put("errorPct", rpm == null || epm == null || rpm.signum() == 0 ? null
                    : epm.multiply(BigDecimal.valueOf(100)).divide(rpm, 1, RoundingMode.HALF_UP));
                s.put("meanMs", number(rs, "mean_ms"));
            }, Timestamp.from(now.minusSeconds(600)));
        this.jdbc.query("SELECT service, greatest(count(DISTINCT started_at) - 1, 0) AS restarts, count(*) FILTER (WHERE status <> 'UP') AS down "
            + "FROM service_health_sample WHERE sampled_at >= ? GROUP BY service", rs -> {
                Map<String, Object> s = services.get(rs.getString("service"));
                if (s != null) {
                    s.put("restarts24h", rs.getLong("restarts"));
                    s.put("minutesNotUp24h", rs.getLong("down"));
                }
            }, dayAgo);
        long step = bucket * 60L;
        this.jdbc.query("SELECT service, floor(extract(epoch FROM sampled_at) / ?) * ? AS bucket, max(heap_used_mb) AS heap, "
            + "avg(requests_per_min) AS rpm, avg(errors_per_min) AS epm, avg(mean_ms) AS ms, avg(cpu_pct) AS cpu, "
            + "bool_and(status = 'UP') AS up FROM service_health_sample WHERE sampled_at >= ? GROUP BY 1, 2 ORDER BY 1, 2", rs -> {
                Map<String, Object> s = services.get(rs.getString("service"));
                if (s == null) {
                    return;
                }
                @SuppressWarnings("unchecked")
                Map<String, List<Object>> series = (Map<String, List<Object>>) s.computeIfAbsent("series", k -> {
                    Map<String, List<Object>> made = new LinkedHashMap<>();
                    for (String key : new String[]{"at", "heapUsedMb", "requestsPerMin", "errorsPerMin", "meanMs", "cpuPct", "up"}) {
                        made.put(key, new ArrayList<>());
                    }
                    return made;
                });
                series.get("at").add(Instant.ofEpochSecond(rs.getLong("bucket")).toString());
                series.get("heapUsedMb").add(integer(rs, "heap"));
                series.get("requestsPerMin").add(rounded(rs.getBigDecimal("rpm"), 1));
                series.get("errorsPerMin").add(rounded(rs.getBigDecimal("epm"), 2));
                series.get("meanMs").add(rounded(rs.getBigDecimal("ms"), 0));
                series.get("cpuPct").add(rounded(rs.getBigDecimal("cpu"), 1));
                series.get("up").add(rs.getBoolean("up"));
            }, step, step, dayAgo);
        List<Map<String, Object>> deploys = this.markPlanned(services, now, dayAgo);
        List<Map<String, Object>> probes = new ArrayList<>();
        this.collector.probes().values().forEach(p -> {
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
        data.put("bucketMinutes", bucket);
        data.put("services", new ArrayList<>(services.values()));
        data.put("deploys", deploys);
        data.put("probes", probes);
        data.put("incidents", alerts);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Platform health.", data), HttpStatus.OK);
    }

    /**
     * The 24 hours' planned deploys, newest first; and on each service that had one, its restarts and minutes not UP that
     * fell inside one (zero for the others).
     */
    private List<Map<String, Object>> markPlanned(Map<String, Map<String, Object>> services, Instant now, Timestamp dayAgo) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, List<DeployWindow>> byService = new LinkedHashMap<>();
        for (DeployWindow window : this.planned.recent(now)) {
            if (window.closes().isBefore(dayAgo.toInstant())) {
                continue;
            }
            out.add(window.toMap(now));
            byService.computeIfAbsent(window.service, k -> new ArrayList<>()).add(window);
        }
        services.values().forEach(s -> {
            s.put("plannedRestarts24h", 0L);
            s.put("plannedMinutesNotUp24h", 0L);
        });
        if (byService.isEmpty()) {
            return out;
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
        return out;
    }

    private static String iso(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant().toString();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static BigDecimal number(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static BigDecimal rounded(BigDecimal value, int places) {
        return value == null ? null : value.setScale(places, RoundingMode.HALF_UP);
    }
}
