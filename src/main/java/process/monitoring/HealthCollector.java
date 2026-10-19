package process.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MIG-355: the platform's health, a minute at a time, from what the services already serve (ServiceReading): one
 * service_health_sample row per service per minute, kept 24 hours; and three probes, each one platform_probe row:
 *
 * <ul>
 *   <li><b>disk</b> (every minute): the free share of the disk Core's container sits on -- the Docker host's, which Postgres,
 *   Kafka and every image share.</li>
 *   <li><b>Kafka lag</b> (every {@value #KAFKA_EVERY} minutes): each consumer group's messages behind on the platform
 *   broker (committed offset to the partition's end), at most {@value #MAX_GROUPS} groups, within an
 *   {@value #KAFKA_DEADLINE_MS} ms deadline. A group that is gone is dropped from the readings.</li>
 *   <li><b>billing reconciliation</b> (hourly): billing-service's POST /internal/billing/reconcile for this month (Chicago),
 *   the same answer etl-platform's scripts/billing-reconcile.sh prints.</li>
 * </ul>
 *
 * Memory: the readings of one pass, the previous counters per service (one entry each) for the rates, nothing else; a
 * Prometheus page is read a line at a time.
 */
@Component
public class HealthCollector {

    public static final String KAFKA_PREFIX = "kafka-lag:";
    public static final String DISK_PREFIX = "disk:";
    public static final String BILLING_PROBE = "billing-reconcile";
    static final int KAFKA_EVERY = 2;
    static final int MAX_GROUPS = 200;
    static final long KAFKA_DEADLINE_MS = 8_000;
    static final Duration BILLING_EVERY = Duration.ofMinutes(55);
    static final Duration KEEP_SAMPLES = Duration.ofHours(24);
    static final Duration TIMEOUT = Duration.ofSeconds(3);
    /** The services and how each is read: name=base|mode, comma-separated (mode: prometheus, metrics, health, plain, self). */
    static final String DEFAULT_TARGETS = "gateway=http://gateway:9098/actuator|health,"
        + "identity=http://identity:9160/api/v1/actuator|health,"
        + "process=self|self,"
        + "notifications=http://notifications:9100/api/v1/actuator|prometheus,"
        + "media=http://media:9110/api/v1/actuator|metrics,"
        + "storage=http://storage:9120/api/v1/actuator|metrics,"
        + "billing=http://billing:9130/api/v1/actuator|metrics,"
        + "analytics=http://analytics:9140/api/v1/actuator|prometheus,"
        + "ai=http://ai:9150/api/v1/actuator|prometheus,"
        + "transcriber=http://transcriber:9170/api/v1/actuator|metrics,"
        + "integration=http://integration:9180/api/v1/actuator|prometheus,"
        + "workflow=http://workflow:9190/api/v1/actuator|prometheus,"
        + "console=http://next-app/health|plain";

    private static final Logger logger = LoggerFactory.getLogger(HealthCollector.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One service to read. */
    static final class Target {
        final String name;
        final String base;
        final String mode;

        Target(String name, String base, String mode) {
            this.name = name;
            this.base = base;
            this.mode = mode;
        }
    }

    /** A service's counters at its previous sample, for the per-minute rates. */
    static final class Previous {
        final Instant at;
        final Instant startedAt;
        final double requests;
        final double errors;

        Previous(Instant at, Instant startedAt, double requests, double errors) {
            this.at = at;
            this.startedAt = startedAt;
            this.requests = requests;
            this.errors = errors;
        }
    }

    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final KafkaProperties kafka;
    private final List<Target> targets;
    private final HttpClient http;
    private final String billingUrl;
    private final String serviceToken;
    private final Map<String, Previous> previous = new ConcurrentHashMap<>();
    private int passes;

    @Autowired
    public HealthCollector(JdbcTemplate jdbc, MeterRegistry registry, KafkaProperties kafka,
        @Value("${process.monitoring.services:" + DEFAULT_TARGETS + "}") String targets,
        @Value("${process.monitoring.billing-url:http://billing:9130/api/v1/internal/billing/reconcile}") String billingUrl,
        @Value("${internal.service-token:}") String serviceToken) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.kafka = kafka;
        this.targets = parse(targets);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        this.billingUrl = billingUrl == null ? "" : billingUrl.trim();
        this.serviceToken = serviceToken == null ? "" : serviceToken.trim();
    }

    static List<Target> parse(String spec) {
        List<Target> out = new ArrayList<>();
        if (spec == null) {
            return out;
        }
        for (String one : spec.split(",")) {
            String entry = one.trim();
            int eq = entry.indexOf('=');
            int bar = entry.lastIndexOf('|');
            if (eq <= 0 || bar <= eq) {
                continue;
            }
            out.add(new Target(entry.substring(0, eq).trim(), entry.substring(eq + 1, bar).trim().replaceAll("/+$", ""),
                entry.substring(bar + 1).trim().toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    List<String> serviceNames() {
        List<String> names = new ArrayList<>();
        this.targets.forEach(t -> names.add(t.name));
        return names;
    }

    /** One pass: every service sampled and stored, then the probes that are due. */
    public List<ServiceReading> collect(Instant now) {
        List<ServiceReading> readings = new ArrayList<>();
        for (Target target : this.targets) {
            ServiceReading reading = this.read(target, now);
            readings.add(reading);
            this.store(reading);
        }
        this.passes++;
        this.disk(now);
        if (this.passes % KAFKA_EVERY == 1) {
            this.kafkaLag(now);
        }
        this.billing(now);
        return readings;
    }

    ServiceReading read(Target target, Instant now) {
        ServiceReading reading = new ServiceReading();
        reading.service = target.name;
        reading.at = now;
        try {
            switch (target.mode) {
                case "self":
                    ServiceReading.self(this.registry, reading);
                    break;
                case "plain":
                    ServiceReading.plain(this.http, target.base, reading, TIMEOUT);
                    break;
                default:
                    ServiceReading.health(this.http, target.base, reading, TIMEOUT);
                    if (!"UNREACHABLE".equals(reading.status)) {
                        if ("prometheus".equals(target.mode)) {
                            ServiceReading.prometheus(this.http, target.base, reading, TIMEOUT, this.serviceToken);
                        } else if ("metrics".equals(target.mode)) {
                            ServiceReading.metrics(this.http, target.base, reading, TIMEOUT, this.serviceToken);
                        }
                    }
            }
        } catch (RuntimeException failed) {
            logger.debug("Could not read {}: {}", target.name, failed.getMessage());
        }
        return reading;
    }

    /** The minute's row; the rates from the counters' change since the service's previous sample (none across a restart). */
    void store(ServiceReading r) {
        BigDecimal perMinute = null;
        BigDecimal errorsPerMinute = null;
        Previous before = this.previous.get(r.service);
        if (r.requests != null && before != null && before.at.isBefore(r.at)
            && (before.startedAt == null ? r.startedAt == null : before.startedAt.equals(r.startedAt))
            && r.requests >= before.requests) {
            double minutes = Duration.between(before.at, r.at).toMillis() / 60_000.0;
            double requests = r.requests - before.requests;
            double errors = Math.max(0, (r.errors == null ? 0 : r.errors) - before.errors);
            if (minutes > 0.1) {
                perMinute = scale(requests / minutes, 2);
                errorsPerMinute = scale(errors / minutes, 2);
            }
        }
        if (r.requests != null) {
            this.previous.put(r.service, new Previous(r.at, r.startedAt, r.requests, r.errors == null ? 0 : r.errors));
        }
        try {
            this.jdbc.update("INSERT INTO service_health_sample (service, sampled_at, status, source, heap_used_mb, heap_max_mb, "
                    + "heap_after_gc_pct, started_at, requests_per_min, errors_per_min) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (service, sampled_at) DO NOTHING",
                r.service, Timestamp.from(r.at), r.status, r.hasFigures() ? "METRICS" : "HEALTH", mb(r.heapUsedBytes),
                mb(r.heapMaxBytes), r.heapAfterGcPct == null ? null : scale(Math.min(999, r.heapAfterGcPct), 1),
                r.startedAt == null ? null : Timestamp.from(r.startedAt), perMinute, errorsPerMinute);
        } catch (RuntimeException failed) {
            logger.warn("Could not store {}'s health sample: {}", r.service, failed.getMessage());
        }
    }

    private static Integer mb(Double bytes) {
        return bytes == null || bytes.isNaN() ? null : (int) Math.round(bytes / (1024 * 1024));
    }

    private static BigDecimal scale(double value, int places) {
        return Double.isNaN(value) || Double.isInfinite(value) ? null : BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP);
    }

    /** Samples older than a day, removed; how many. */
    public int purge(Instant now) {
        return this.jdbc.update("DELETE FROM service_health_sample WHERE sampled_at < ?", Timestamp.from(now.minus(KEEP_SAMPLES)));
    }

    // ------------------------------------------------------------------------------------------------------ probes

    void disk(Instant now) {
        File root = new File("/");
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        if (total <= 0) {
            return;
        }
        double pct = 100.0 * free / total;
        this.probe(DISK_PREFIX + "/", now, pct >= 0, pct, String.format(Locale.ROOT,
            "%.1f GB free of %.1f GB on the disk Core's container sits on (the Docker host's, shared with Postgres and Kafka).",
            free / 1e9, total / 1e9));
    }

    void kafkaLag(Instant now) {
        if (this.kafka == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + KAFKA_DEADLINE_MS;
        Map<String, Object> config = new HashMap<>(this.kafka.buildAdminProperties());
        config.put("request.timeout.ms", 5000);
        config.put("default.api.timeout.ms", (int) KAFKA_DEADLINE_MS);
        Map<String, Long> lagByGroup = new LinkedHashMap<>();
        Map<String, Integer> partitionsBehind = new HashMap<>();
        try (AdminClient admin = AdminClient.create(config)) {
            Collection<ConsumerGroupListing> groups = admin.listConsumerGroups().all().get(left(deadline), TimeUnit.MILLISECONDS);
            Map<String, Map<TopicPartition, OffsetAndMetadata>> committed = new LinkedHashMap<>();
            int looked = 0;
            for (ConsumerGroupListing group : groups) {
                if (looked++ >= MAX_GROUPS || left(deadline) <= 0) {
                    break;
                }
                Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(group.groupId()).partitionsToOffsetAndMetadata()
                    .get(left(deadline), TimeUnit.MILLISECONDS);
                if (!offsets.isEmpty()) {
                    committed.put(group.groupId(), offsets);
                }
            }
            Map<TopicPartition, OffsetSpec> wanted = new HashMap<>();
            committed.values().forEach(offsets -> offsets.keySet().forEach(tp -> wanted.put(tp, OffsetSpec.latest())));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends = wanted.isEmpty() ? new HashMap<>()
                : admin.listOffsets(wanted).all().get(left(deadline), TimeUnit.MILLISECONDS);
            for (Map.Entry<String, Map<TopicPartition, OffsetAndMetadata>> group : committed.entrySet()) {
                long lag = 0;
                int behind = 0;
                for (Map.Entry<TopicPartition, OffsetAndMetadata> partition : group.getValue().entrySet()) {
                    ListOffsetsResult.ListOffsetsResultInfo end = ends.get(partition.getKey());
                    if (end == null || partition.getValue() == null) {
                        continue;
                    }
                    long gap = Math.max(0, end.offset() - partition.getValue().offset());
                    lag += gap;
                    if (gap > 0) {
                        behind++;
                    }
                }
                lagByGroup.put(group.getKey(), lag);
                partitionsBehind.put(group.getKey(), behind);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception failed) {
            logger.warn("Could not read Kafka consumer lag: {}", failed.getMessage());
            this.probe(KAFKA_PREFIX + "(broker)", now, false, null, "The broker could not be asked: " + failed.getMessage());
            return;
        }
        for (Map.Entry<String, Long> group : lagByGroup.entrySet()) {
            this.probe(KAFKA_PREFIX + group.getKey(), now, true, (double) group.getValue(), String.format(Locale.ROOT,
                "%d messages behind across %d partition(s).", group.getValue(), partitionsBehind.getOrDefault(group.getKey(), 0)));
        }
        // Groups that are gone (and the broker's own failure line, once it answers again) leave the readings.
        List<String> keep = new ArrayList<>();
        lagByGroup.keySet().forEach(g -> keep.add(KAFKA_PREFIX + g));
        List<String> stale = this.jdbc.queryForList("SELECT probe FROM platform_probe WHERE probe LIKE 'kafka-lag:%'", String.class);
        for (String probe : stale) {
            if (!keep.contains(probe)) {
                this.jdbc.update("DELETE FROM platform_probe WHERE probe = ?", probe);
            }
        }
    }

    private static long left(long deadline) {
        return Math.max(1, deadline - System.currentTimeMillis());
    }

    /** This month's billing reconciliation, at most hourly. */
    void billing(Instant now) {
        if (this.billingUrl.isEmpty() || this.serviceToken.isEmpty()) {
            return;
        }
        List<Timestamp> last = this.jdbc.queryForList("SELECT read_at FROM platform_probe WHERE probe = ?", Timestamp.class, BILLING_PROBE);
        if (!last.isEmpty() && Duration.between(last.get(0).toInstant(), now).compareTo(BILLING_EVERY) < 0) {
            return;
        }
        String period = YearMonth.from(now.atZone(ZoneId.of("America/Chicago"))).toString();
        try {
            HttpResponse<String> answer = this.http.send(HttpRequest.newBuilder(URI.create(this.billingUrl)).timeout(Duration.ofSeconds(60))
                .header("X-Internal-Token", this.serviceToken).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"period\":\"" + period + "\"}")).build(), HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() != 200) {
                this.probe(BILLING_PROBE, now, true, null, "Billing answered HTTP " + answer.statusCode() + "; not measured this hour.");
                return;
            }
            JsonNode body = JSON.readTree(answer.body());
            boolean ok = body.path("ok").asBoolean(true);
            List<String> off = new ArrayList<>();
            for (JsonNode workspace : body.path("workspaces")) {
                List<String> what = new ArrayList<>();
                for (String field : new String[]{"dailyStatus", "status"}) {
                    String value = workspace.path(field).asText("");
                    if (value.contains("mismatch")) {
                        what.add(value);
                    }
                }
                String breakdown = workspace.path("breakdown").path("status").asText("");
                if (breakdown.contains("mismatch")) {
                    what.add(breakdown);
                }
                if (!what.isEmpty()) {
                    off.add("workspace " + workspace.path("tenantId").asText() + " (" + String.join(", ", what) + ")");
                }
            }
            String detail = ok ? "The month " + period + " agrees: daily lines, invoices and usage events."
                : "The month " + period + " does not agree: " + (off.isEmpty() ? "see scripts/billing-reconcile.sh" : String.join("; ", off))
                    + ". Run etl-platform's scripts/billing-reconcile.sh " + period + " for the figures.";
            this.probe(BILLING_PROBE, now, ok, (double) off.size(), AlertIncidents.cut(detail, 2000));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception failed) {
            logger.warn("Could not ask billing for its reconciliation: {}", failed.getMessage());
            this.probe(BILLING_PROBE, now, true, null, "Billing could not be asked: " + AlertIncidents.cut(failed.getMessage(), 300));
        }
    }

    void probe(String name, Instant at, boolean ok, Double reading, String detail) {
        this.jdbc.update("INSERT INTO platform_probe (probe, read_at, ok, reading, detail) VALUES (?, ?, ?, ?, ?) ON CONFLICT (probe) DO UPDATE "
                + "SET read_at = excluded.read_at, ok = excluded.ok, reading = excluded.reading, detail = excluded.detail",
            AlertIncidents.cut(name, 80), Timestamp.from(at), ok, reading == null ? null : scale(reading, 2), AlertIncidents.cut(detail, 2000));
    }

    /** Every probe's latest reading. */
    public Map<String, RuleChecks.Probe> probes() {
        Map<String, RuleChecks.Probe> out = new LinkedHashMap<>();
        this.jdbc.query("SELECT probe, read_at, ok, reading, detail FROM platform_probe ORDER BY probe", rs -> {
            RuleChecks.Probe p = new RuleChecks.Probe();
            p.name = rs.getString(1);
            p.at = rs.getTimestamp(2).toInstant();
            p.ok = rs.getBoolean(3);
            BigDecimal reading = rs.getBigDecimal(4);
            p.reading = reading == null ? null : reading.doubleValue();
            p.detail = rs.getString(5);
            out.put(p.name, p);
        });
        return out;
    }

    /** The last hour of samples, newest first per service: what the platform rules read. */
    public Map<String, List<RuleChecks.Sample>> recent(Instant now) {
        Map<String, List<RuleChecks.Sample>> out = new LinkedHashMap<>();
        this.jdbc.query("SELECT service, sampled_at, status, heap_used_mb, heap_max_mb, heap_after_gc_pct, started_at FROM service_health_sample "
            + "WHERE sampled_at >= ? ORDER BY service, sampled_at DESC", rs -> {
                RuleChecks.Sample s = new RuleChecks.Sample();
                s.service = rs.getString(1);
                s.at = rs.getTimestamp(2).toInstant();
                s.status = rs.getString(3);
                int used = rs.getInt(4);
                s.heapUsedMb = rs.wasNull() ? null : used;
                int max = rs.getInt(5);
                s.heapMaxMb = rs.wasNull() ? null : max;
                BigDecimal gc = rs.getBigDecimal(6);
                s.heapAfterGcPct = gc == null ? null : gc.doubleValue();
                Timestamp started = rs.getTimestamp(7);
                s.startedAt = started == null ? null : started.toInstant();
                out.computeIfAbsent(s.service, k -> new ArrayList<>()).add(s);
            }, Timestamp.from(now.minus(Duration.ofHours(1))));
        return out;
    }

    /** When the newest sample was taken, if any: the platform rules wait while the collector is not sampling. */
    public Optional<Instant> newestSample() {
        Timestamp at = this.jdbc.queryForObject("SELECT max(sampled_at) FROM service_health_sample", Timestamp.class);
        return Optional.ofNullable(at).map(Timestamp::toInstant);
    }
}
