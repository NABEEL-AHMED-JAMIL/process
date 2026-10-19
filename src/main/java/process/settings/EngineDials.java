package process.settings;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.Optional;

/**
 * Owner #24 (2026-10-19): the engine's values for the dials of EngineDial, as the engine reads them -- a workspace's own
 * value (engine_setting_override) where the dial allows one, else the platform's (orchestration_setting), else the
 * default (the environment's property when it is set, else the built-in). Every value is clamped into its range, so a
 * row written by hand cannot take the engine outside it.
 *
 * <p>Cached: the platform's values and every override are read in one pass and kept {@link #TTL}; a change made here
 * (EngineSettingsService) clears this instance's copy at once, and another Core instance reads it within the TTL. A
 * read that fails keeps the last values (or the defaults), and says so once a minute at most.
 */
@Component
public class EngineDials {

    static final Duration TTL = Duration.ofSeconds(30);
    private static final Logger logger = LoggerFactory.getLogger(EngineDials.class);

    /** One read of the stored values. */
    static final class Snapshot {
        final Map<EngineDial, Long> platform;
        final Map<Long, Map<EngineDial, Long>> overrides;
        final Instant at;

        Snapshot(Map<EngineDial, Long> platform, Map<Long, Map<EngineDial, Long>> overrides, Instant at) {
            this.platform = platform;
            this.overrides = overrides;
            this.at = at;
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<EngineDial, Long> defaults = new EnumMap<>(EngineDial.class);
    private volatile Snapshot snapshot;
    private volatile Instant warned = Instant.EPOCH;

    public EngineDials(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        for (EngineDial dial : EngineDial.values()) {
            long value = dial.builtIn();
            if (dial.property() != null && environment != null) {
                Long set = environment.getProperty(dial.property(), Long.class);
                if (set != null) {
                    value = set;
                }
            }
            this.defaults.put(dial, dial.clamp(value));
        }
    }

    /** Defaults only, nothing stored (tests, and a validator built without the database). */
    public static EngineDials defaults() {
        return new EngineDials(null, null);
    }

    /** What the dial is when no platform administrator set it. */
    public long defaultOf(EngineDial dial) {
        return this.defaults.get(dial);
    }

    /** The platform's value: the stored one, else the default. */
    public long platform(EngineDial dial) {
        Long stored = this.read().platform.get(dial);
        return stored == null ? this.defaultOf(dial) : dial.clamp(stored);
    }

    /** The value for one workspace: its own where the dial allows one, else the platform's. */
    public long forWorkspace(EngineDial dial, Long tenantId) {
        if (dial.perWorkspace() && tenantId != null) {
            Long own = this.read().overrides.getOrDefault(tenantId, Collections.emptyMap()).get(dial);
            if (own != null) {
                return dial.clamp(own);
            }
        }
        return this.platform(dial);
    }

    public int platformInt(EngineDial dial) {
        return (int) this.platform(dial);
    }

    public int forWorkspaceInt(EngineDial dial, Long tenantId) {
        return (int) this.forWorkspace(dial, tenantId);
    }

    /** The stored platform value, or null when the dial is at its default. */
    public Long storedPlatform(EngineDial dial) {
        return this.read().platform.get(dial);
    }

    /** Every workspace's own values. */
    public Map<Long, Map<EngineDial, Long>> overrides() {
        return this.read().overrides;
    }

    /** Forget the cached values: the next read goes to the database (a change was just made). */
    public void invalidate() {
        this.snapshot = null;
    }

    private Snapshot read() {
        Snapshot now = this.snapshot;
        if (now != null && now.at.plus(TTL).isAfter(Instant.now())) {
            return now;
        }
        if (this.jdbc == null) {
            Snapshot empty = new Snapshot(new EnumMap<>(EngineDial.class), Collections.emptyMap(), Instant.now());
            this.snapshot = empty;
            return empty;
        }
        try {
            Map<EngineDial, Long> platform = new EnumMap<>(EngineDial.class);
            this.jdbc.query("SELECT setting_key, setting_value FROM orchestration_setting", rs -> {
                String key = rs.getString(1);
                String value = rs.getString(2);
                EngineDial.of(key).ifPresent(dial -> parse(value).ifPresent(v -> platform.put(dial, v)));
            });
            Map<Long, Map<EngineDial, Long>> overrides = new HashMap<>();
            List<Object[]> rows = RowSecurity.acrossTenants("the engine settings (owner #24) read every workspace's own engine values "
                + "(engine_setting_override: a key and a number each), cached for the step engine and the platform screen",
                () -> this.jdbc.query("SELECT tenant_id, setting_key, setting_value FROM engine_setting_override",
                    (rs, i) -> new Object[]{rs.getLong(1), rs.getString(2), rs.getString(3)}));
            for (Object[] row : rows) {
                EngineDial.of((String) row[1]).filter(EngineDial::perWorkspace).ifPresent(dial -> parse((String) row[2])
                    .ifPresent(v -> overrides.computeIfAbsent((Long) row[0], k -> new EnumMap<>(EngineDial.class)).put(dial, v)));
            }
            Snapshot fresh = new Snapshot(platform, overrides, Instant.now());
            this.snapshot = fresh;
            return fresh;
        } catch (RuntimeException failed) {
            Instant at = Instant.now();
            if (Duration.between(this.warned, at).compareTo(Duration.ofMinutes(1)) >= 0) {
                this.warned = at;
                logger.warn("The engine settings could not be read; the engine keeps {}: {}", now == null ? "the defaults" : "the last values",
                    failed.getMessage());
            }
            Snapshot kept = now != null ? new Snapshot(now.platform, now.overrides, at)
                : new Snapshot(new EnumMap<>(EngineDial.class), Collections.emptyMap(), at);
            this.snapshot = kept;
            return kept;
        }
    }

    private static Optional<Long> parse(String value) {
        if (value == null || !value.trim().matches("[0-9]{1,18}")) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value.trim()));
    }
}
