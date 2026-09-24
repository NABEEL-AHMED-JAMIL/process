package process.settings;

import org.springframework.stereotype.Component;
import process.model.pojo.PipelineConfig;
import process.model.repository.PipelineConfigRepository;

import java.util.Optional;

/**
 * The save-time rules a task payload is held to about configuration (MIG-167), for addSourceTask, updateSourceTask
 * and the bulk upload alike -- the way PlatformDatabases is wired into all three:
 * - every ${config:...} / ${secret:...} names a valid key (ConfigReferences.malformed);
 * - a credential-named tag holds nothing or exactly one ${secret:KEY}, never the credential (credentialLiteral);
 * - every reference names an entry that exists in the task's own workspace, of the kind it is referenced as.
 * The first two need no workspace (payloadRefusal); the third does (refusal).
 */
@Component
public class TaskConfigRules {

    private final PipelineConfigRepository entries;

    public TaskConfigRules(PipelineConfigRepository entries) {
        this.entries = entries;
    }

    /** The rules that need no workspace. */
    public static Optional<String> payloadRefusal(String payload) {
        Optional<String> malformed = ConfigReferences.malformed(payload);
        return malformed.isPresent() ? malformed : ConfigReferences.credentialLiteral(payload);
    }

    /** Every rule, for a payload saved into this workspace. */
    public Optional<String> refusal(String payload, Long tenantId) {
        Optional<String> refused = payloadRefusal(payload);
        if (refused.isPresent()) {
            return refused;
        }
        return this.missingEntry(payload, tenantId);
    }

    /** A reference to an entry this workspace does not have, or has as the other kind. */
    public Optional<String> missingEntry(String payload, Long tenantId) {
        for (ConfigReferences.Reference reference : ConfigReferences.in(payload)) {
            Optional<PipelineConfig> entry = tenantId == null ? Optional.empty()
                : this.entries.findByTenantIdAndConfigKey(tenantId, reference.key);
            if (!entry.isPresent() || !reference.kind.entryKind.equals(entry.get().getKind())) {
                return Optional.of(String.format("The task references %s, which is not a %s of this workspace. Add it in "
                    + "Configuration values first.", reference, reference.kind == ConfigReferences.Kind.SECRET ? "secret" : "value"));
            }
        }
        return Optional.empty();
    }
}
