package process.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Owner #21 (2026-10-19): the version each service runs, for Platform › Service health. etl-platform's scripts/deploy.sh
 * writes it when a deploy ends well -- one field per service of the Redis hash {@value #KEY}, the same way it announces a
 * planned deploy (PlannedDeploys), because Core may be the one being replaced:
 * <pre>
 *   HSET monitoring:service-versions &lt;service&gt; {"version":"e7023e7","branch":"media-extraction","at":1760900000}
 * </pre>
 * A service deployed before this existed has no version until its next deploy (shown as unknown). Redis unreachable: no
 * versions, nothing else changes.
 */
@Component
public class ServiceVersions {

    public static final String KEY = "monitoring:service-versions";
    private static final Pattern SERVICE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._:/@+-]{1,64}");
    private static final Logger logger = LoggerFactory.getLogger(ServiceVersions.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One service's running version: what was deployed, from which branch, when. */
    public static final class Version {
        public final String version;
        public final String branch;
        public final Instant deployedAt;

        Version(String version, String branch, Instant deployedAt) {
            this.version = version;
            this.branch = branch;
            this.deployedAt = deployedAt;
        }
    }

    private final RedisTemplate<String, String> redis;
    private final Map<String, Version> fixed;

    @Autowired
    public ServiceVersions(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
        this.fixed = null;
    }

    private ServiceVersions(Map<String, Version> fixed) {
        this.redis = null;
        this.fixed = fixed;
    }

    /** These versions and no others (the tests). */
    public static ServiceVersions of(Map<String, Version> versions) {
        return new ServiceVersions(new LinkedHashMap<>(versions));
    }

    public static ServiceVersions none() {
        return new ServiceVersions(Collections.emptyMap());
    }

    /** Every service's version that is known, by the monitor's service name. */
    public Map<String, Version> all() {
        if (this.fixed != null) {
            return this.fixed;
        }
        Map<String, Version> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<Object, Object> field : this.redis.opsForHash().entries(KEY).entrySet()) {
                String service = String.valueOf(field.getKey());
                Version version = parse(String.valueOf(field.getValue()));
                if (SERVICE.matcher(service).matches() && version != null) {
                    out.put(service, version);
                }
            }
        } catch (RuntimeException failed) {
            logger.debug("The services' versions could not be read from Redis: {}", failed.getMessage());
            return Collections.emptyMap();
        }
        return out;
    }

    /** One field's value; null when it is not a version (a hand-written field, a wrong shape). */
    static Version parse(String value) {
        try {
            JsonNode node = JSON.readTree(value);
            String version = node.path("version").asText("");
            if (!VERSION.matcher(version).matches()) {
                return null;
            }
            String branch = node.path("branch").asText("");
            long at = node.path("at").asLong(0);
            return new Version(version, VERSION.matcher(branch).matches() ? branch : null, at > 0 ? Instant.ofEpochSecond(at) : null);
        } catch (Exception notJson) {
            return null;
        }
    }
}
