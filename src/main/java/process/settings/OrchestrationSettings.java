package process.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.util.ProcessUtil;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * Core's engine dials and watermarks, orchestration_setting (MIG-136, MIG-167) -- the only class that writes the
 * table, through narrow doors: setQueueFetchLimit, setDial and resetDial for the engine settings screen (owner #24: only
 * the dials EngineDial allows), and writeWatermark for the two crons that own a watermark each (WatermarkWritersTest).
 * The only delete is resetDial's, of a dial that falls back to its default; nothing is renamed, and a watermark is never
 * removed.
 *
 * Read straight from the table on every use, never cached; EngineDials keeps the dials' values for 30 seconds.
 */
@Component
public class OrchestrationSettings {

    /** One row, as the settings screen shows it. */
    public static final class Setting {

        public final String key;
        public final String value;
        public final String description;
        public final Timestamp updatedAt;
        public final Long updatedBy;

        public Setting(String key, String value, String description, Timestamp updatedAt, Long updatedBy) {
            this.key = key;
            this.value = value;
            this.description = description;
            this.updatedAt = updatedAt;
            this.updatedBy = updatedBy;
        }
    }

    private final JdbcTemplate jdbc;

    public OrchestrationSettings(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> value(String key) {
        List<String> found = this.jdbc.queryForList("SELECT setting_value FROM orchestration_setting WHERE setting_key = ?",
            String.class, key);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public Optional<Setting> find(String key) {
        return this.read("WHERE setting_key = ?", key).stream().findFirst();
    }

    public List<Setting> all() {
        return this.read("ORDER BY setting_key");
    }

    /** The settings screen's only write (EngineSettingsService validates the number first). */
    public void setQueueFetchLimit(long limit, Long by) {
        this.jdbc.update("INSERT INTO orchestration_setting (setting_key, setting_value, description, updated_at, updated_by) "
                + "VALUES (?, ?, ?, now(), ?) ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, "
                + "updated_at = now(), updated_by = EXCLUDED.updated_by",
            ProcessUtil.QUEUE_FETCH_LIMIT, Long.toString(limit),
            "How many queued runs one dispatch pass takes. A positive whole number; anything else dispatches 1000 and warns once per pass.",
            by);
    }

    /**
     * Owner #24: a platform value for one of the engine dials (EngineDial), validated by EngineSettingsService first. The
     * fetch limit keeps its own row and description, as setQueueFetchLimit writes it.
     */
    public void setDial(EngineDial dial, long value, Long by) {
        if (dial == EngineDial.QUEUE_FETCH_LIMIT) {
            this.setQueueFetchLimit(value, by);
            return;
        }
        this.jdbc.update("INSERT INTO orchestration_setting (setting_key, setting_value, description, updated_at, updated_by) "
                + "VALUES (?, ?, ?, now(), ?) ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, "
                + "updated_at = now(), updated_by = EXCLUDED.updated_by",
            dial.key(), Long.toString(value), dial.description(), by);
    }

    /**
     * Owner #24: back to the default -- the row goes, so the environment's value (or the built-in) applies again. The fetch
     * limit, which the dispatcher reads from its row on every pass, is set to its default instead of removed.
     */
    public void resetDial(EngineDial dial, Long by) {
        if (dial == EngineDial.QUEUE_FETCH_LIMIT) {
            this.setQueueFetchLimit(dial.builtIn(), by);
            return;
        }
        this.jdbc.update("DELETE FROM orchestration_setting WHERE setting_key = ?", dial.key());
    }

    /** A cron moving its own watermark. Only ModelApplication and AuditLogSyncCron call this (WatermarkWritersTest). */
    public void writeWatermark(Watermark watermark, String value) {
        this.jdbc.update("INSERT INTO orchestration_setting (setting_key, setting_value, description, updated_at) "
                + "VALUES (?, ?, ?, now()) ON CONFLICT (setting_key) DO UPDATE SET setting_value = EXCLUDED.setting_value, "
                + "updated_at = now(), updated_by = NULL",
            watermark.name(), value, watermark.description);
    }

    /** The enqueuer's start-up: sets the scheduler's watermark only when there is none, never over one. */
    public boolean writeWatermarkIfAbsent(Watermark watermark, String value) {
        return this.jdbc.update("INSERT INTO orchestration_setting (setting_key, setting_value, description, updated_at) "
                + "VALUES (?, ?, ?, now()) ON CONFLICT (setting_key) DO NOTHING",
            watermark.name(), value, watermark.description) > 0;
    }

    private List<Setting> read(String tail, Object... arguments) {
        return this.jdbc.query("SELECT setting_key, setting_value, description, updated_at, updated_by FROM orchestration_setting " + tail,
            (row, i) -> new Setting(row.getString("setting_key"), row.getString("setting_value"), row.getString("description"),
                row.getTimestamp("updated_at"), row.getObject("updated_by", Long.class)),
            arguments);
    }
}
