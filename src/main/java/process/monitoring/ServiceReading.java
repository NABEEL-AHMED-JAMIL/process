package process.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * MIG-355: one service's health and figures, read from what it already serves -- Spring Boot Actuator, nothing new:
 *
 * <ul>
 *   <li>{@code health}: GET .../actuator/health; the status it answers, or UNREACHABLE when it does not answer.</li>
 *   <li>{@code prometheus}: GET .../actuator/prometheus, read a line at a time and never held whole (a service's page is
 *   200-500 KB of histograms): the heap in use and its maximum, the heap after GC (long-lived pool), CPU, live threads,
 *   the JVM's start, and http.server.requests' count, 5xx count and seconds -- the actuator's own calls left out.</li>
 *   <li>{@code metrics}: the same figures from .../actuator/metrics/&lt;name&gt;, a small JSON answer each, for a service
 *   whose Micrometer has no Prometheus registry (media, storage, billing, the transcriber).</li>
 *   <li>{@code self}: Core's own, from its MeterRegistry and the JVM.</li>
 *   <li>{@code plain}: a page that answers 200 is UP (the console's nginx).</li>
 * </ul>
 * A service whose figures cannot be read is still sampled for its health (source HEALTH).
 */
public final class ServiceReading {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LINE = 64 * 1024;

    public String service;
    public Instant at;
    public String status = "UNKNOWN";
    public String source = "HEALTH";
    public Integer responseMs;
    public Double heapUsedBytes;
    public Double heapMaxBytes;
    public Double heapAfterGcPct;
    public Double cpuPct;
    public Integer threads;
    public Instant startedAt;
    /** Cumulative since the JVM started: requests, 5xx answers and seconds spent (actuator calls left out). */
    public Double requests;
    public Double errors;
    public Double seconds;

    boolean hasFigures() {
        return this.heapUsedBytes != null || this.requests != null || this.startedAt != null;
    }

    // ------------------------------------------------------------------------------------------------------ health

    static void health(HttpClient http, String base, ServiceReading out, Duration timeout) {
        long started = System.nanoTime();
        try {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(base + "/health")).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            out.responseMs = (int) ((System.nanoTime() - started) / 1_000_000);
            String status = null;
            try {
                JsonNode body = JSON.readTree(answer.body());
                status = body.path("status").asText(null);
            } catch (IOException | RuntimeException unreadable) {
                status = null;
            }
            if (status == null) {
                status = answer.statusCode() == 200 ? "UP" : "DOWN";
            }
            out.status = known(status);
        } catch (IOException failed) {
            out.status = "UNREACHABLE";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            out.status = "UNREACHABLE";
        }
    }

    static void plain(HttpClient http, String url, ServiceReading out, Duration timeout) {
        long started = System.nanoTime();
        try {
            HttpResponse<Void> answer = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            out.responseMs = (int) ((System.nanoTime() - started) / 1_000_000);
            out.status = answer.statusCode() == 200 ? "UP" : "DOWN";
        } catch (IOException failed) {
            out.status = "UNREACHABLE";
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            out.status = "UNREACHABLE";
        }
    }

    static String known(String status) {
        String upper = status.trim().toUpperCase(Locale.ROOT);
        switch (upper) {
            case "UP":
            case "DOWN":
            case "OUT_OF_SERVICE":
            case "UNREACHABLE":
                return upper;
            default:
                return "UNKNOWN";
        }
    }

    // ------------------------------------------------------------------------------------------------------ prometheus

    /** Reads the Prometheus page a line at a time; false when it could not be read (the figures stay unset). */
    static boolean prometheus(HttpClient http, String base, ServiceReading out, Duration timeout) {
        try {
            HttpResponse<InputStream> answer = http.send(HttpRequest.newBuilder(URI.create(base + "/prometheus")).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = answer.body()) {
                if (answer.statusCode() != 200) {
                    return false;
                }
                parsePrometheus(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), out);
            }
            out.source = "METRICS";
            return true;
        } catch (IOException failed) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The figures from a Prometheus text page; every other line is skipped as it is read. */
    static void parsePrometheus(BufferedReader lines, ServiceReading out) throws IOException {
        double heapUsed = 0;
        double heapMax = 0;
        boolean sawHeap = false;
        double requests = 0;
        double errors = 0;
        double seconds = 0;
        boolean sawRequests = false;
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.isEmpty() || line.charAt(0) == '#' || line.length() > MAX_LINE) {
                continue;
            }
            if (line.startsWith("jvm_memory_used_bytes{")) {
                if (line.contains("area=\"heap\"")) {
                    heapUsed += value(line);
                    sawHeap = true;
                }
            } else if (line.startsWith("jvm_memory_max_bytes{")) {
                double v = value(line);
                if (line.contains("area=\"heap\"") && v > 0) {
                    heapMax += v;
                }
            } else if (line.startsWith("jvm_memory_usage_after_gc_percent{")) {
                if (line.contains("area=\"heap\"") && line.contains("pool=\"long-lived\"")) {
                    out.heapAfterGcPct = value(line) * 100;
                }
            } else if (line.startsWith("process_start_time_seconds")) {
                out.startedAt = Instant.ofEpochMilli(Math.round(value(line) * 1000));
            } else if (line.startsWith("process_cpu_usage")) {
                double v = value(line);
                out.cpuPct = Double.isNaN(v) || v < 0 ? null : v * 100;
            } else if (line.startsWith("jvm_threads_live_threads")) {
                out.threads = (int) value(line);
            } else if (line.startsWith("http_server_requests_seconds_count{") || line.startsWith("http_server_requests_seconds_sum{")) {
                String uri = label(line, "uri");
                if (uri != null && uri.startsWith("/actuator")) {
                    continue;
                }
                double v = value(line);
                if (line.startsWith("http_server_requests_seconds_count{")) {
                    requests += v;
                    sawRequests = true;
                    String status = label(line, "status");
                    if (status != null && status.startsWith("5")) {
                        errors += v;
                    }
                } else {
                    seconds += v;
                }
            }
        }
        if (sawHeap) {
            out.heapUsedBytes = heapUsed;
            out.heapMaxBytes = heapMax > 0 ? heapMax : null;
        }
        // A service that has answered nothing yet has no series at all: none is zero.
        out.requests = requests;
        out.errors = errors;
        out.seconds = seconds;
        if (!sawRequests) {
            out.requests = 0.0;
        }
    }

    static double value(String line) {
        int space = line.lastIndexOf(' ');
        if (space < 0) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(line.substring(space + 1).trim());
        } catch (NumberFormatException unreadable) {
            return Double.NaN;
        }
    }

    static String label(String line, String name) {
        String key = name + "=\"";
        int at = line.indexOf(key);
        while (at > 0 && line.charAt(at - 1) != '{' && line.charAt(at - 1) != ',') {
            at = line.indexOf(key, at + 1);
        }
        if (at < 0) {
            return null;
        }
        int start = at + key.length();
        int end = line.indexOf('"', start);
        return end < 0 ? null : line.substring(start, end);
    }

    // ------------------------------------------------------------------------------------------------------ actuator metrics

    /** The figures from /actuator/metrics, one small answer each; false when the endpoint is not served. */
    static boolean metrics(HttpClient http, String base, ServiceReading out, Duration timeout) {
        JsonNode used = metric(http, base, "jvm.memory.used", timeout, "area:heap");
        if (used == null) {
            return false;
        }
        out.heapUsedBytes = statistic(used, "VALUE");
        JsonNode max = metric(http, base, "jvm.memory.max", timeout, "area:heap");
        Double maxValue = max == null ? null : statistic(max, "VALUE");
        out.heapMaxBytes = maxValue != null && maxValue > 0 ? maxValue : null;
        JsonNode afterGc = metric(http, base, "jvm.memory.usage.after.gc", timeout, "area:heap", "pool:long-lived");
        Double ratio = afterGc == null ? null : statistic(afterGc, "VALUE");
        out.heapAfterGcPct = ratio == null ? null : ratio * 100;
        JsonNode start = metric(http, base, "process.start.time", timeout);
        Double startSeconds = start == null ? null : statistic(start, "VALUE");
        out.startedAt = startSeconds == null ? null : Instant.ofEpochMilli(Math.round(startSeconds * 1000));
        JsonNode cpu = metric(http, base, "process.cpu.usage", timeout);
        Double cpuValue = cpu == null ? null : statistic(cpu, "VALUE");
        out.cpuPct = cpuValue == null || cpuValue < 0 ? null : cpuValue * 100;
        JsonNode threads = metric(http, base, "jvm.threads.live", timeout);
        Double live = threads == null ? null : statistic(threads, "VALUE");
        out.threads = live == null ? null : live.intValue();
        JsonNode requests = metric(http, base, "http.server.requests", timeout);
        double count = 0;
        double total = 0;
        if (requests != null) {
            count = orZero(statistic(requests, "COUNT"));
            total = orZero(statistic(requests, "TOTAL_TIME"));
            // The actuator's own calls (this collector's, the container's health check) are not the service's work.
            for (JsonNode tag : requests.path("availableTags")) {
                if (!"uri".equals(tag.path("tag").asText())) {
                    continue;
                }
                for (JsonNode uri : tag.path("values")) {
                    if (uri.asText().startsWith("/actuator")) {
                        JsonNode own = metric(http, base, "http.server.requests", timeout, "uri:" + uri.asText());
                        if (own != null) {
                            count -= orZero(statistic(own, "COUNT"));
                            total -= orZero(statistic(own, "TOTAL_TIME"));
                        }
                    }
                }
            }
        }
        JsonNode failed = metric(http, base, "http.server.requests", timeout, "outcome:SERVER_ERROR");
        out.requests = Math.max(0, count);
        out.seconds = Math.max(0, total);
        out.errors = failed == null ? 0 : orZero(statistic(failed, "COUNT"));
        out.source = "METRICS";
        return true;
    }

    private static double orZero(Double value) {
        return value == null || value.isNaN() ? 0 : value;
    }

    private static JsonNode metric(HttpClient http, String base, String name, Duration timeout, String... tags) {
        StringBuilder url = new StringBuilder(base).append("/metrics/").append(name);
        for (int i = 0; i < tags.length; i++) {
            url.append(i == 0 ? '?' : '&').append("tag=").append(URLEncoder.encode(tags[i], StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<String> answer = http.send(HttpRequest.newBuilder(URI.create(url.toString())).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            return answer.statusCode() == 200 ? JSON.readTree(answer.body()) : null;
        } catch (IOException failed) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static Double statistic(JsonNode metric, String name) {
        for (JsonNode measurement : metric.path("measurements")) {
            if (name.equals(measurement.path("statistic").asText())) {
                return measurement.path("value").asDouble();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------ self

    /** Core's own figures: its registry and its JVM. */
    static void self(MeterRegistry registry, ServiceReading out) {
        out.status = "UP";
        out.source = "METRICS";
        Runtime runtime = Runtime.getRuntime();
        out.heapUsedBytes = (double) (runtime.totalMemory() - runtime.freeMemory());
        out.heapMaxBytes = (double) runtime.maxMemory();
        out.startedAt = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        out.threads = ManagementFactory.getThreadMXBean().getThreadCount();
        if (registry == null) {
            return;
        }
        Gauge afterGc = Search.in(registry).name("jvm.memory.usage.after.gc").tag("area", "heap").tag("pool", "long-lived").gauge();
        out.heapAfterGcPct = afterGc == null ? null : afterGc.value() * 100;
        Gauge cpu = Search.in(registry).name("process.cpu.usage").gauge();
        out.cpuPct = cpu == null || Double.isNaN(cpu.value()) ? null : cpu.value() * 100;
        double count = 0;
        double errors = 0;
        double seconds = 0;
        List<Timer> timers = new ArrayList<>(Search.in(registry).name("http.server.requests").timers());
        for (Timer timer : timers) {
            String uri = timer.getId().getTag("uri");
            if (uri != null && uri.startsWith("/actuator")) {
                continue;
            }
            count += timer.count();
            seconds += timer.totalTime(TimeUnit.SECONDS);
            String status = timer.getId().getTag("status");
            if (status != null && status.startsWith("5")) {
                errors += timer.count();
            }
        }
        out.requests = count;
        out.errors = errors;
        out.seconds = seconds;
    }
}
