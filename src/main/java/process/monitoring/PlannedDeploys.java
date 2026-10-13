package process.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The planned deploy windows (DeployWindow) etl-platform's scripts/deploy.sh announces, read from one Redis hash.
 *
 * <p>Why Redis and not an endpoint: deploy.sh deploys Core too, and while Core is being replaced there is nobody to call.
 * So deploy.sh writes the window itself, on the host, with redis-cli inside the redis container -- the same way whatever
 * it deploys -- and Core reads it each minute. One field per deploy:
 * <pre>
 *   HSET monitoring:planned-deploys &lt;service&gt;:&lt;startEpoch&gt;
 *        {"service":"transcriber","start":1760360000,"expires":1760360900,"end":null,"outcome":null}
 * </pre>
 * and the same field again with "end" and "outcome" once the new container is healthy (or the deploy failed). Times are
 * epoch seconds. A field older than {@link #KEEP}, or one that does not parse, is removed here; deploy.sh refreshes the
 * hash's own expiry (a week) on every write, so a platform whose Core never runs again does not keep it.
 *
 * <p>Redis unreachable: no windows, so every restart alerts as it did before -- failing towards telling, not towards
 * silence. Read once a minute (the alert pass) and by Platform › Reliability; at most {@link #MAX} windows.
 */
@Component
public class PlannedDeploys {

    public static final String KEY = "monitoring:planned-deploys";
    /** A window is kept a day and an hour: Platform › Reliability shows the last 24 hours. */
    static final Duration KEEP = Duration.ofHours(25);
    static final int MAX = 500;
    private static final Pattern SERVICE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Duration WARN_EVERY = Duration.ofMinutes(10);

    private static final Logger logger = LoggerFactory.getLogger(PlannedDeploys.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RedisTemplate<String, String> redis;
    private final List<DeployWindow> fixed;
    private volatile Instant warned = Instant.EPOCH;

    @Autowired
    public PlannedDeploys(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
        this.fixed = null;
    }

    private PlannedDeploys(List<DeployWindow> fixed) {
        this.redis = null;
        this.fixed = fixed;
    }

    /** No windows: every restart alerts (the tests, and anything built without Redis). */
    public static PlannedDeploys none() {
        return new PlannedDeploys(Collections.emptyList());
    }

    /** These windows and no others (the tests). */
    public static PlannedDeploys of(List<DeployWindow> windows) {
        return new PlannedDeploys(new ArrayList<>(windows));
    }

    /** The windows of the last {@link #KEEP}, newest first. */
    public List<DeployWindow> recent(Instant now) {
        List<DeployWindow> out = new ArrayList<>();
        if (this.fixed != null) {
            out.addAll(this.fixed);
        } else {
            Map<Object, Object> fields;
            try {
                fields = this.redis.opsForHash().entries(KEY);
            } catch (RuntimeException failed) {
                if (Duration.between(this.warned, now).compareTo(WARN_EVERY) >= 0) {
                    this.warned = now;
                    logger.warn("The planned deploy windows could not be read from Redis; restarts alert as if there were none: {}",
                        failed.getMessage());
                }
                return Collections.emptyList();
            }
            List<Object> stale = new ArrayList<>();
            for (Map.Entry<Object, Object> field : fields.entrySet()) {
                Optional<DeployWindow> window = parse(String.valueOf(field.getValue()));
                if (!window.isPresent() || window.get().start.isBefore(now.minus(KEEP))) {
                    stale.add(field.getKey());
                } else {
                    out.add(window.get());
                }
            }
            if (!stale.isEmpty()) {
                try {
                    this.redis.opsForHash().delete(KEY, stale.toArray());
                } catch (RuntimeException failed) {
                    logger.debug("Could not remove {} old planned deploy windows: {}", stale.size(), failed.getMessage());
                }
            }
        }
        out.removeIf(w -> w.start.isBefore(now.minus(KEEP)));
        out.sort(Comparator.comparing((DeployWindow w) -> w.start).reversed());
        return out.size() > MAX ? new ArrayList<>(out.subList(0, MAX)) : out;
    }

    /** The windows of the last {@link #KEEP} by service, newest first. */
    public Map<String, List<DeployWindow>> byService(Instant now) {
        Map<String, List<DeployWindow>> out = new LinkedHashMap<>();
        for (DeployWindow window : this.recent(now)) {
            out.computeIfAbsent(window.service, k -> new ArrayList<>()).add(window);
        }
        return out;
    }

    /** One field's value; empty when it is not a window (it is then removed). */
    static Optional<DeployWindow> parse(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            String service = node.path("service").asText("");
            long start = node.path("start").asLong(0);
            long expires = node.path("expires").asLong(0);
            if (!SERVICE.matcher(service).matches() || start <= 0 || expires <= start) {
                return Optional.empty();
            }
            long end = node.path("end").asLong(0);
            String outcome = node.path("outcome").isTextual() ? node.path("outcome").asText() : null;
            if (outcome != null && !outcome.matches("[a-z]{1,16}")) {
                outcome = null;
            }
            return Optional.of(new DeployWindow(service, Instant.ofEpochSecond(start), Instant.ofEpochSecond(expires),
                end > 0 && end >= start ? Instant.ofEpochSecond(end) : null, outcome));
        } catch (Exception notJson) {
            return Optional.empty();
        }
    }
}
