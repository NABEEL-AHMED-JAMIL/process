package process.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;
import process.security.TenantContext;

import java.util.Optional;

/**
 * ZT-M21 (owner 2026-10-26): the files of a pipeline marked sensitive -- what Save File keeps (and its per-row files), an
 * item's files and zips, a report's PDF, a run's outputs -- are its real output (a signed report) and are never masked, so
 * the bytes are a permission's: the access profile's "Download sensitive files" (page key sensitive-files, asked of the page
 * gate as {@value #PATH}). A workspace administrator holds it, as every key; a member without it sees that the file exists
 * (name, size, type) and is refused the download, View and preview in {@link #PERSON_REFUSAL}. An API client holds no
 * profile and no scope for it yet: every customer-API file route of a sensitive pipeline answers {@link #API_REFUSAL}
 * (files:sensitive goes into platform-commons' ApiScopes with the next commons bump). Sensitive is the pipeline's
 * settings.sensitivity, the same flag its rows are masked by (the definition the run ran).
 */
@Component
public class SensitiveFiles {

    private static final Logger logger = LoggerFactory.getLogger(SensitiveFiles.class);

    /** The page gate's path for the permission (identity's PageKey.SENSITIVE_FILES owns /sensitiveFiles.json). */
    public static final String PATH = "/sensitiveFiles.json/download";

    public static final String PERSON_REFUSAL = "This pipeline's files are sensitive: downloading, viewing or previewing them needs the "
        + "Download sensitive files permission. Ask your workspace admin.";

    public static final String API_REFUSAL = "This pipeline's files are sensitive; download them in the console, or ask for API access when "
        + "it's offered.";

    public static final String UNCHECKED = "Whether you may download this pipeline's files could not be checked; try again shortly.";

    private final StepStore steps;
    private final PipelineDefinitionStore definitions;
    private IdentityPort identity;

    public SensitiveFiles(StepStore steps, PipelineDefinitionStore definitions) {
        this.steps = steps;
        this.definitions = definitions;
    }

    @Autowired(required = false)
    public void useIdentity(IdentityPort identity) {
        this.identity = identity;
    }

    /** Whether the run's pipeline -- the definition it ran -- is marked sensitive. */
    public boolean sensitiveRun(long jobQueueId) {
        try {
            Optional<PipelineDefinitionStore.Stored> stored = this.steps.pinnedDefinition(jobQueueId).flatMap(this.definitions::byId);
            return stored.isPresent() && sensitive(stored.get().definition());
        } catch (RuntimeException unreadable) {
            // A definition that cannot be read is treated as sensitive: its files are refused rather than handed out.
            logger.warn("Run {}: its definition could not be read for its files' sensitivity: {}", jobQueueId, unreadable.getMessage());
            return true;
        }
    }

    public static boolean sensitive(PipelineDefinition definition) {
        return definition != null && "sensitive".equals(definition.effectiveSettings().effectiveSensitivity());
    }

    /** Why the signed-in person may not have this run's files; empty when they may (or the run is not sensitive). */
    public Optional<String> refusalForCaller(long jobQueueId) {
        return this.sensitiveRun(jobQueueId) ? this.personRefusal() : Optional.empty();
    }

    /** Whether the signed-in person holds Download sensitive files (an administrator always does). */
    public boolean mayDownload() {
        return !this.personRefusal().isPresent();
    }

    /** Why the signed-in person does not hold the permission (or it could not be asked: refused, fail closed); empty when held. */
    public Optional<String> personRefusal() {
        String role = TenantContext.getUserRole();
        if (!"TENANT_USER".equals(role)) {
            return Optional.empty();
        }
        if (this.identity == null) {
            return Optional.of(UNCHECKED);
        }
        try {
            boolean held = this.identity.pageDecision(role, TenantContext.getAppUserId(), PATH).isAllowed();
            return held ? Optional.empty() : Optional.of(PERSON_REFUSAL);
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("Identity could not say whether {} may download sensitive files: {}", TenantContext.getAppUserId(),
                unavailable.getMessage());
            return Optional.of(UNCHECKED);
        }
    }
}
