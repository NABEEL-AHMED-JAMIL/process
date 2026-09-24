package process.settings;

import java.util.Collections;
import java.sql.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.pojo.PipelineConfig;
import process.model.repository.PipelineConfigRepository;
import process.model.repository.SourceTaskRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * The configuration store, as the console manages it (MIG-167): a workspace's values and secrets, which its task
 * payloads reference as ${config:KEY} and ${secret:KEY}.
 *
 * A secret is write-only. It is sealed by EncryptionUtil under the current key on the way in, stored only in
 * value_sealed, and nothing here ever puts it -- or its sealed form -- in an answer or a log line: the console gets
 * "set on <date> by <who>" and may replace it, never read it. The one reader is RunConfigResolver, for a live run
 * whose task references it. Log lines name keys, never values.
 */
@Service
public class PipelineConfigService {

    static final int MAX_VALUE = 4000;
    static final int MAX_DESCRIPTION = 255;
    static final String MASK_REFUSAL = "That is the mask the console shows for a secret, not a secret. Type the new secret, "
        + "or leave the field empty to keep the current one.";

    private static final Logger logger = LoggerFactory.getLogger(PipelineConfigService.class);

    private final PipelineConfigRepository entries;
    private final SourceTaskRepository tasks;
    private final EncryptionUtil encryption;
    private final IdentityPort identity;
    private final UserNameResolver names;

    public PipelineConfigService(PipelineConfigRepository entries, SourceTaskRepository tasks, EncryptionUtil encryption,
        IdentityPort identity, UserNameResolver names) {
        this.entries = entries;
        this.tasks = tasks;
        this.encryption = encryption;
        this.identity = identity;
        this.names = names;
    }

    public ResponseDto list(Long requestedTenantId) {
        Long tenantId = ActingWorkspace.forList(requestedTenantId);
        List<PipelineConfig> rows = tenantId == null
            ? this.entries.findAllByOrderByTenantIdAscConfigKeyAsc()
            : this.entries.findByTenantIdOrderByConfigKeyAsc(tenantId);
        return new ResponseDto(SUCCESS, "Data fetch successfully.", this.answers(rows));
    }

    @Transactional
    public ResponseDto add(PipelineConfigDto request) {
        String key = request.getKey() == null ? "" : request.getKey().trim();
        if (!ConfigReferences.KEY.matcher(key).matches()) {
            return new ResponseDto(ERROR, "A key is UPPER_SNAKE: A-Z, 0-9 and _, starting with a letter, at most 64 characters.");
        }
        String kind = request.getKind();
        if (!PipelineConfig.VALUE.equals(kind) && !PipelineConfig.SECRET.equals(kind)) {
            return new ResponseDto(ERROR, "The kind of an entry is VALUE or SECRET.");
        }
        String valueRefusal = refuseValue(request.getValue());
        if (valueRefusal != null) {
            return new ResponseDto(ERROR, valueRefusal);
        }
        String descriptionRefusal = refuseDescription(request.getDescription());
        if (descriptionRefusal != null) {
            return new ResponseDto(ERROR, descriptionRefusal);
        }
        if (PipelineConfig.SECRET.equals(kind) && looksLikeTheMask(request.getValue())) {
            return new ResponseDto(ERROR, MASK_REFUSAL);
        }
        if (PipelineConfig.SECRET.equals(kind) && !this.encryption.hasCurrentKey()) {
            return new ResponseDto(ERROR, "Secrets cannot be stored until PROCESS_ENCRYPTION_KEY is configured.");
        }
        Long[] tenant = new Long[1];
        String workspaceRefusal = ActingWorkspace.resolve(this.identity, request.getTenantId(), id -> tenant[0] = id);
        if (workspaceRefusal != null) {
            return new ResponseDto(ERROR, workspaceRefusal);
        }
        if (this.entries.findByTenantIdAndConfigKey(tenant[0], key).isPresent()) {
            return new ResponseDto(ERROR, String.format("%s already exists in this workspace.", key));
        }
        PipelineConfig entry = new PipelineConfig();
        entry.setTenantId(tenant[0]);
        entry.setConfigKey(key);
        entry.setKind(kind);
        this.write(entry, request.getValue());
        entry.setDescription(blankToNull(request.getDescription()));
        PipelineConfig saved = this.entries.save(entry);
        logger.info("Configuration {} {} added to workspace {}.", kind.toLowerCase(), key, tenant[0]);
        return new ResponseDto(SUCCESS, String.format("%s saved.", key), this.answers(listOf(saved)).get(0));
    }

    @Transactional
    public ResponseDto update(PipelineConfigDto request) {
        Optional<PipelineConfig> found = request.getId() == null ? Optional.empty() : this.entries.findById(request.getId());
        if (!found.isPresent() || !ActingWorkspace.mayTouch(found.get().getTenantId())) {
            return new ResponseDto(ERROR, String.format("Configuration entry %s not found.", request.getId()));
        }
        PipelineConfig entry = found.get();
        if (request.getKey() != null && !request.getKey().trim().equals(entry.getConfigKey())) {
            return new ResponseDto(ERROR, String.format("%s cannot be renamed -- tasks reference it by that key. Add a new "
                + "entry instead.", entry.getConfigKey()));
        }
        if (request.getKind() != null && !request.getKind().equals(entry.getKind())) {
            return new ResponseDto(ERROR, String.format("The kind of %s cannot change. Add a new entry instead.", entry.getConfigKey()));
        }
        boolean newValue = request.getValue() != null && !request.getValue().trim().isEmpty();
        if (!entry.isSecret() && request.getValue() != null && !newValue) {
            return new ResponseDto(ERROR, "A value cannot be blank.");
        }
        if (newValue) {
            String valueRefusal = refuseValue(request.getValue());
            if (valueRefusal != null) {
                return new ResponseDto(ERROR, valueRefusal);
            }
            if (entry.isSecret() && looksLikeTheMask(request.getValue())) {
                return new ResponseDto(ERROR, MASK_REFUSAL);
            }
            if (entry.isSecret() && !this.encryption.hasCurrentKey()) {
                return new ResponseDto(ERROR, "Secrets cannot be stored until PROCESS_ENCRYPTION_KEY is configured.");
            }
        }
        String descriptionRefusal = refuseDescription(request.getDescription());
        if (descriptionRefusal != null) {
            return new ResponseDto(ERROR, descriptionRefusal);
        }
        if (newValue) {
            this.write(entry, request.getValue());
        }
        if (request.getDescription() != null) {
            entry.setDescription(blankToNull(request.getDescription()));
        }
        PipelineConfig saved = this.entries.save(entry);
        logger.info("Configuration {} {} of workspace {} updated{}.", entry.getKind().toLowerCase(), entry.getConfigKey(),
            entry.getTenantId(), newValue ? (entry.isSecret() ? " (secret replaced)" : " (value changed)") : "");
        return new ResponseDto(SUCCESS, String.format("%s saved.", entry.getConfigKey()), this.answers(listOf(saved)).get(0));
    }

    @Transactional
    public ResponseDto delete(Long id) {
        Optional<PipelineConfig> found = id == null ? Optional.empty() : this.entries.findById(id);
        if (!found.isPresent() || !ActingWorkspace.mayTouch(found.get().getTenantId())) {
            return new ResponseDto(ERROR, String.format("Configuration entry %s not found.", id));
        }
        PipelineConfig entry = found.get();
        long using = this.usedBy(entry);
        if (using > 0) {
            return new ResponseDto(ERROR, String.format("%d task%s still use%s %s. Change %s first.", using, using == 1 ? "" : "s",
                using == 1 ? "s" : "", entry.getConfigKey(), using == 1 ? "that task" : "those tasks"));
        }
        this.entries.delete(entry);
        logger.info("Configuration {} {} deleted from workspace {}.", entry.getKind().toLowerCase(), entry.getConfigKey(),
            entry.getTenantId());
        return new ResponseDto(SUCCESS, String.format("%s deleted.", entry.getConfigKey()));
    }

    /** A VALUE keeps its value; a SECRET is sealed under the current key and nothing else of it is kept. */
    private void write(PipelineConfig entry, String value) {
        entry.setValueSetAt(new Timestamp(System.currentTimeMillis()));
        entry.setValueSetBy(TenantContext.getAppUserId());
        if (entry.isSecret()) {
            entry.setValue(null);
            entry.setValueSealed(this.encryption.encrypt(value));
        } else {
            entry.setValue(value);
            entry.setValueSealed(null);
        }
    }

    private long usedBy(PipelineConfig entry) {
        String reference = String.format("${%s:%s}", entry.isSecret() ? "secret" : "config", entry.getConfigKey());
        return this.tasks.countLiveTasksWithPayloadContaining(entry.getTenantId(), reference);
    }

    private List<PipelineConfigDto> answers(List<PipelineConfig> rows) {
        Set<Long> people = new HashSet<>();
        for (PipelineConfig row : rows) {
            people.add(row.getCreatedBy());
            people.add(row.getValueSetBy());
        }
        people.remove(null);
        Map<Long, String> byId = people.isEmpty() ? Collections.emptyMap() : this.names.namesFor(people);
        List<PipelineConfigDto> answers = new ArrayList<>();
        for (PipelineConfig row : rows) {
            PipelineConfigDto dto = new PipelineConfigDto();
            dto.setId(row.getId());
            dto.setTenantId(row.getTenantId());
            dto.setKey(row.getConfigKey());
            dto.setKind(row.getKind());
            if (row.isSecret()) {
                dto.setSecretSet(row.getValueSealed() != null);
            } else {
                dto.setValue(row.getValue());
            }
            dto.setDescription(row.getDescription());
            dto.setCreatedAt(ActingWorkspace.iso(row.getCreatedAt()));
            dto.setCreatedByName(row.getCreatedBy() == null ? null : byId.get(row.getCreatedBy()));
            dto.setSetAt(ActingWorkspace.iso(row.getValueSetAt() != null ? row.getValueSetAt() : row.getCreatedAt()));
            dto.setSetByName(row.getValueSetBy() == null ? null : byId.get(row.getValueSetBy()));
            dto.setUsedByTasks(this.usedBy(row));
            answers.add(dto);
        }
        return answers;
    }

    private static String refuseValue(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "A value is required.";
        }
        if (value.length() > MAX_VALUE) {
            return String.format("A value is at most %d characters.", MAX_VALUE);
        }
        return null;
    }

    /**
     * The old Lookups screen echoed its mask back as a "new" secret, and the real one was gone after the first edit
     * (EncryptedLookupEditTest, retired with it). This API never sends a mask to echo, but a value that is nothing
     * but mask characters is still refused rather than stored as the secret.
     */
    private static boolean looksLikeTheMask(String value) {
        return value != null && value.trim().matches("[\u2022*]+");
    }

    private static String refuseDescription(String description) {
        return description != null && description.length() > MAX_DESCRIPTION
            ? String.format("A description is at most %d characters.", MAX_DESCRIPTION) : null;
    }

    private static String blankToNull(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }

    private static List<PipelineConfig> listOf(PipelineConfig row) {
        List<PipelineConfig> one = new ArrayList<>();
        one.add(row);
        return one;
    }
}
