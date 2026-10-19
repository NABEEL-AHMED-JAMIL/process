package process.settings;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.UserNameResolver;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Platform › Engine settings (MIG-167; owner #24, 2026-10-19), platform administrators only.
 *
 * <ul>
 *   <li>The dials of EngineDial -- an allow-list: each with its value, its default, its range, its unit, when a change
 *   takes effect, and, where the dial allows one, the workspaces that have their own value. A value is written as digits
 *   inside the range or refused in words; every change and every reset is recorded (engine_setting_audit) with the old
 *   and new value, who and when.</li>
 *   <li>The two watermarks, shown and never written: each is its cron's (owner's D4).</li>
 * </ul>
 * Anything else -- a secret, a URL, a key -- is not an engine setting and stays in the environment.
 */
@Service
public class EngineSettingsService {

    private static final Logger logger = LoggerFactory.getLogger(EngineSettingsService.class);
    private static final List<String> WATERMARKS = Arrays.asList(Watermark.SCHEDULER_LAST_RUN_TIME.name(),
        Watermark.AUDIT_LOG_SYNC_LAST_RUN_TIME.name());

    private final OrchestrationSettings settings;
    private final EngineDials dials;
    private final EngineSettingStore store;
    private final UserNameResolver names;

    public EngineSettingsService(OrchestrationSettings settings, EngineDials dials, EngineSettingStore store, UserNameResolver names) {
        this.settings = settings;
        this.dials = dials;
        this.store = store;
        this.names = names;
    }

    public ResponseDto list() {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can see engine settings.");
        }
        Map<String, OrchestrationSettings.Setting> rows = new LinkedHashMap<>();
        for (OrchestrationSettings.Setting row : this.settings.all()) {
            rows.put(row.key, row);
        }
        Map<Long, Map<String, Object[]>> stamps = this.store.overrideStamps();
        Set<Long> people = new HashSet<>();
        rows.values().forEach(row -> people.add(row.updatedBy));
        stamps.values().forEach(byKey -> byKey.values().forEach(stamp -> people.add((Long) stamp[1])));
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        this.dials.invalidate();
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngineDial dial : EngineDial.values()) {
            out.add(this.dialRow(dial, rows.get(dial.key()), stamps, byId));
        }
        List<EngineSettingDto> watermarks = new ArrayList<>();
        for (String key : WATERMARKS) {
            OrchestrationSettings.Setting row = rows.get(key);
            if (row != null) {
                watermarks.add(watermark(row, byId));
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settings", out);
        data.put("watermarks", watermarks);
        data.put("cacheSeconds", EngineDials.TTL.getSeconds());
        return new ResponseDto(SUCCESS, "Engine settings.", data);
    }

    /** A dial's platform value ({@code tenantId} null) or one workspace's own; digits inside the range. */
    public ResponseDto update(String key, String value, Long tenantId) {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can change engine settings.");
        }
        Optional<EngineDial> found = this.dialOf(key);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, refusalFor(key));
        }
        EngineDial dial = found.get();
        if (tenantId != null && !dial.perWorkspace()) {
            return new ResponseDto(ERROR, String.format("%s is the platform's alone: a workspace cannot have its own.", dial.label()));
        }
        if (tenantId != null && tenantId <= 0) {
            return new ResponseDto(ERROR, "Not a workspace.");
        }
        Optional<String> problem = dial.problem(value == null ? null : value.trim());
        if (problem.isPresent()) {
            return new ResponseDto(ERROR, problem.get());
        }
        long typed = Long.parseLong(value.trim());
        Long by = TenantContext.getAppUserId();
        String before;
        if (tenantId == null) {
            Long stored = this.dials.storedPlatform(dial);
            before = stored == null ? null : Long.toString(stored);
            this.settings.setDial(dial, typed, by);
        } else {
            before = this.store.override(tenantId, dial).orElse(null);
            this.store.setOverride(tenantId, dial, typed, by);
        }
        this.store.record(dial, tenantId, "SET", before, Long.toString(typed), by);
        this.dials.invalidate();
        logger.info("Engine setting {} set to {} {} by user {} (was {}).", dial.key(), typed,
            tenantId == null ? "for the platform" : "for workspace " + tenantId, by, before == null ? "the default" : before);
        return new ResponseDto(SUCCESS, String.format("%s is now %,d %s%s. %s", dial.label(), typed, dial.unit(),
            tenantId == null ? "" : " in this workspace", dial.takesEffect()), this.one(dial));
    }

    /** Back to the default (the platform's value), or a workspace back to the platform's value. */
    public ResponseDto reset(String key, Long tenantId) {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can change engine settings.");
        }
        Optional<EngineDial> found = this.dialOf(key);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, refusalFor(key));
        }
        EngineDial dial = found.get();
        Long by = TenantContext.getAppUserId();
        String before;
        if (tenantId == null) {
            Long stored = this.dials.storedPlatform(dial);
            before = stored == null ? null : Long.toString(stored);
            if (before == null || (dial == EngineDial.QUEUE_FETCH_LIMIT && stored == dial.builtIn())) {
                return new ResponseDto(SUCCESS, String.format("%s is already at its default.", dial.label()), this.one(dial));
            }
            this.settings.resetDial(dial, by);
        } else {
            before = this.store.override(tenantId, dial).orElse(null);
            if (before == null) {
                return new ResponseDto(SUCCESS, String.format("This workspace has no value of its own for %s.", dial.label()), this.one(dial));
            }
            this.store.removeOverride(tenantId, dial);
        }
        this.dials.invalidate();
        String after = Long.toString(tenantId == null ? this.dials.defaultOf(dial) : this.dials.platform(dial));
        this.store.record(dial, tenantId, "RESET", before, after, by);
        logger.info("Engine setting {} reset {} by user {} (was {}, now {}).", dial.key(),
            tenantId == null ? "for the platform" : "for workspace " + tenantId, by, before, after);
        return new ResponseDto(SUCCESS, String.format("%s is back to %s (%s %s). %s", dial.label(),
            tenantId == null ? "its default" : "the platform's value", after, dial.unit(), dial.takesEffect()), this.one(dial));
    }

    public ResponseDto history(String key, Integer limit) {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can see engine settings.");
        }
        if (key != null && !key.trim().isEmpty() && !EngineDial.of(key).isPresent()) {
            return new ResponseDto(ERROR, refusalFor(key));
        }
        List<EngineSettingStore.Change> changes = this.store.history(key == null || key.trim().isEmpty() ? null : key.trim(),
            limit == null ? 100 : limit);
        Set<Long> people = new HashSet<>();
        changes.forEach(c -> people.add(c.by));
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EngineSettingStore.Change c : changes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.id);
            row.put("key", c.key);
            row.put("label", EngineDial.of(c.key).map(EngineDial::label).orElse(c.key));
            row.put("tenantId", c.tenantId);
            row.put("action", c.action);
            row.put("oldValue", c.oldValue);
            row.put("newValue", c.newValue);
            row.put("changedBy", c.by);
            row.put("changedByName", c.by == null ? null : byId.get(c.by));
            row.put("changedAt", ActingWorkspace.iso(c.at));
            out.add(row);
        }
        return new ResponseDto(SUCCESS, "Engine setting changes.", out);
    }

    private Optional<EngineDial> dialOf(String key) {
        return key != null && Watermark.isWatermark(key.trim()) ? Optional.empty() : EngineDial.of(key);
    }

    private static String refusalFor(String key) {
        if (key != null && Watermark.isWatermark(key.trim())) {
            return String.format("%s is written only by %s; it cannot be set here.", key.trim(),
                Watermark.SCHEDULER_LAST_RUN_TIME.name().equals(key.trim()) ? "the enqueuer" : "AuditLogSyncCron");
        }
        return String.format("No engine setting named %s can be changed here: secrets and infrastructure settings stay in the "
            + "environment.", key);
    }

    private Map<String, Object> one(EngineDial dial) {
        this.dials.invalidate();
        Optional<OrchestrationSettings.Setting> row = this.settings.find(dial.key());
        Map<Long, Map<String, Object[]>> stamps = this.store.overrideStamps();
        Set<Long> people = new HashSet<>();
        row.ifPresent(r -> people.add(r.updatedBy));
        stamps.values().forEach(byKey -> {
            Object[] stamp = byKey.get(dial.key());
            if (stamp != null) {
                people.add((Long) stamp[1]);
            }
        });
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        return this.dialRow(dial, row.orElse(null), stamps, byId);
    }

    private Map<String, Object> dialRow(EngineDial dial, OrchestrationSettings.Setting row, Map<Long, Map<String, Object[]>> stamps,
        Map<Long, String> byId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", dial.key());
        out.put("label", dial.label());
        out.put("description", dial.description());
        out.put("unit", dial.unit());
        out.put("min", dial.min());
        out.put("max", dial.max());
        out.put("defaultValue", this.dials.defaultOf(dial));
        out.put("defaultFrom", dial.property() == null ? "built-in" : dial.property());
        Long stored = this.dials.storedPlatform(dial);
        out.put("value", this.dials.platform(dial));
        out.put("atDefault", stored == null || (dial == EngineDial.QUEUE_FETCH_LIMIT && stored == this.dials.defaultOf(dial)));
        out.put("perWorkspace", dial.perWorkspace());
        out.put("takesEffect", dial.takesEffect());
        out.put("updatedAt", row == null ? null : ActingWorkspace.iso(row.updatedAt));
        out.put("updatedByName", row == null || row.updatedBy == null ? null : byId.get(row.updatedBy));
        List<Map<String, Object>> overrides = new ArrayList<>();
        if (dial.perWorkspace()) {
            this.dials.overrides().forEach((tenantId, values) -> {
                Long own = values.get(dial);
                if (own == null) {
                    return;
                }
                Map<String, Object> o = new LinkedHashMap<>();
                o.put("tenantId", tenantId);
                o.put("value", dial.clamp(own));
                Object[] stamp = stamps.getOrDefault(tenantId, Collections.emptyMap()).get(dial.key());
                o.put("updatedAt", stamp == null ? null : ActingWorkspace.iso((Timestamp) stamp[0]));
                o.put("updatedByName", stamp == null || stamp[1] == null ? null : byId.get((Long) stamp[1]));
                overrides.add(o);
            });
        }
        out.put("overrides", overrides);
        return out;
    }

    private static EngineSettingDto watermark(OrchestrationSettings.Setting row, Map<Long, String> byId) {
        EngineSettingDto dto = new EngineSettingDto();
        dto.setKey(row.key);
        dto.setValue(row.value);
        dto.setDescription(row.description);
        dto.setEditable(false);
        dto.setUpdatedAt(ActingWorkspace.iso(row.updatedAt));
        dto.setUpdatedByName(row.updatedBy == null ? null : byId.get(row.updatedBy));
        return dto;
    }
}
