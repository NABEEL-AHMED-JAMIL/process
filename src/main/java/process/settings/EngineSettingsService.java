package process.settings;

import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * The engine settings screen (MIG-167, platform admin only). QUEUE_FETCH_LIMIT is editable, as a whole number from 1
 * to 1000000 written exactly as digits -- "5,000", a stray space or a decimal is refused where the operator can see
 * it, rather than read by the dispatcher as "fall back to 1000". The watermarks are shown and never written: each is
 * its cron's (owner's D4). Nothing here deletes or renames a setting.
 */
@Service
public class EngineSettingsService {

    static final long MAX_QUEUE_FETCH_LIMIT = 1_000_000L;

    private static final Logger logger = LoggerFactory.getLogger(EngineSettingsService.class);

    /** In the order the screen shows them. */
    private static final List<String> SHOWN = Arrays.asList(ProcessUtil.QUEUE_FETCH_LIMIT,
        Watermark.SCHEDULER_LAST_RUN_TIME.name(), Watermark.AUDIT_LOG_SYNC_LAST_RUN_TIME.name());

    private final OrchestrationSettings settings;
    private final UserNameResolver names;

    public EngineSettingsService(OrchestrationSettings settings, UserNameResolver names) {
        this.settings = settings;
        this.names = names;
    }

    public ResponseDto list() {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can see engine settings.");
        }
        List<OrchestrationSettings.Setting> rows = new ArrayList<>(this.settings.all());
        rows.sort((a, b) -> Integer.compare(order(a.key), order(b.key)));
        Set<Long> people = new HashSet<>();
        rows.forEach(row -> people.add(row.updatedBy));
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        List<EngineSettingDto> answers = new ArrayList<>();
        for (OrchestrationSettings.Setting row : rows) {
            answers.add(answer(row, byId));
        }
        return new ResponseDto(SUCCESS, "Data fetch successfully.", answers);
    }

    public ResponseDto update(String key, String value) {
        if (!TenantContext.isPlatformAdmin()) {
            return new ResponseDto(ERROR, "Only a platform administrator can change engine settings.");
        }
        if (key != null && Watermark.isWatermark(key)) {
            return new ResponseDto(ERROR, String.format("%s is written only by %s; it cannot be set here.", key,
                Watermark.SCHEDULER_LAST_RUN_TIME.name().equals(key) ? "the enqueuer" : "AuditLogSyncCron"));
        }
        if (!ProcessUtil.QUEUE_FETCH_LIMIT.equals(key)) {
            return new ResponseDto(ERROR, String.format("No engine setting named %s can be changed here.", key));
        }
        Long limit = value != null && value.matches("[0-9]{1,7}") ? Long.valueOf(value) : null;
        if (limit == null || limit < 1 || limit > MAX_QUEUE_FETCH_LIMIT) {
            return new ResponseDto(ERROR, String.format("QUEUE_FETCH_LIMIT must be a whole number from 1 to %d, written as "
                + "digits only. Got \"%s\".", MAX_QUEUE_FETCH_LIMIT, value));
        }
        this.settings.setQueueFetchLimit(limit, TenantContext.getAppUserId());
        logger.info("QUEUE_FETCH_LIMIT set to {} by user {}.", limit, TenantContext.getAppUserId());
        Optional<OrchestrationSettings.Setting> now = this.settings.find(key);
        return new ResponseDto(SUCCESS, String.format("QUEUE_FETCH_LIMIT is now %d.", limit),
            now.map(row -> answer(row, Collections.emptyMap())).orElse(null));
    }

    private static EngineSettingDto answer(OrchestrationSettings.Setting row, Map<Long, String> byId) {
        EngineSettingDto dto = new EngineSettingDto();
        dto.setKey(row.key);
        dto.setValue(row.value);
        dto.setDescription(row.description);
        dto.setEditable(ProcessUtil.QUEUE_FETCH_LIMIT.equals(row.key));
        dto.setUpdatedAt(ActingWorkspace.iso(row.updatedAt));
        dto.setUpdatedByName(row.updatedBy == null ? null : byId.get(row.updatedBy));
        return dto;
    }

    private static int order(String key) {
        int at = SHOWN.indexOf(key);
        return at < 0 ? SHOWN.size() : at;
    }
}
