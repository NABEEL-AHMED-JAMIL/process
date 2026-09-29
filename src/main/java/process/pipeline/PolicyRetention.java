package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.ai.AiPort;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * The live {@link RetentionPolicy} (MIG-243): the workspace's retention days per sensitivity level, as ai-service's data
 * policy says them (POST /internal/ai/dataPolicy/retention, service token). Core keeps no copy of the policy: one read
 * per workspace is remembered for five minutes, so a busy engine asks once, and a saved change reaches new runs soon.
 * When ai-service cannot say, the pipeline's own hours stand (and that is remembered for a minute, so an outage is not
 * asked about on every step).
 */
@Component
public class PolicyRetention implements RetentionPolicy {

    static final long REMEMBER_MILLIS = 5 * 60 * 1000L;
    static final long REMEMBER_FAILURE_MILLIS = 60 * 1000L;

    private static final Logger logger = LoggerFactory.getLogger(PolicyRetention.class);

    private final AiPort ai;
    private final LongSupplier clock;
    private final Map<Long, Remembered> remembered = new ConcurrentHashMap<>();

    @Autowired
    public PolicyRetention(AiPort ai) {
        this(ai, System::currentTimeMillis);
    }

    PolicyRetention(AiPort ai, LongSupplier clock) {
        this.ai = ai;
        this.clock = clock;
    }

    private static final class Remembered {
        final Map<String, Integer> days;
        final long until;

        Remembered(Map<String, Integer> days, long until) {
            this.days = days;
            this.until = until;
        }
    }

    @Override
    public Duration retentionFor(long tenantId, PipelineDefinition.Settings settings) {
        Integer days = this.days(tenantId).get(settings.effectiveSensitivity());
        return RetentionPolicy.combine(settings.getDatasetRetentionHours(), days);
    }

    /** The workspace's days per level (a level with none is absent or null). */
    Map<String, Integer> days(long tenantId) {
        long now = this.clock.getAsLong();
        Remembered known = this.remembered.get(tenantId);
        if (known != null && known.until > now) {
            return known.days;
        }
        Map<String, Integer> days;
        long keep;
        try {
            Map<String, Integer> read = this.ai.retentionDays(tenantId);
            days = read == null ? Collections.emptyMap() : read;
            keep = REMEMBER_MILLIS;
        } catch (AiPort.AiUnavailableException | RuntimeException ex) {
            logger.warn("Workspace {}: the data policy's retention could not be read, so the pipeline's own stands: {}", tenantId,
                ex.getMessage());
            days = Collections.emptyMap();
            keep = REMEMBER_FAILURE_MILLIS;
        }
        this.remembered.put(tenantId, new Remembered(days, now + keep));
        return days;
    }
}
