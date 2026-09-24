package process.config;

import org.slf4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unique constraints that settle a race between two writers, and what the loser is told (MIG-71).
 *
 * The service layer checks each rule first; the constraint is what holds when two instances pass that
 * check at the same moment. The loser gets a 409 with a refusal it can act on, never the 500 an
 * unexpected failure gets (the translation DEF-008 asks of billing, applied to Core's two invariants).
 * Any other violation is still a server error, answered opaquely like the rest.
 */
public final class RaceRefusals {

    private static final Map<String, String> REFUSALS;

    static {
        Map<String, String> refusals = new LinkedHashMap<>();
        refusals.put("uk_scheduler_job_id",
            "This job already has a schedule -- another change saved one a moment ago. Reload the job and edit that schedule.");
        refusals.put("ux_kcp_one_default_per_tenant",
            "Another default Kafka connection was set for this workspace at the same moment. Reload and choose again.");
        refusals.put("ux_kcp_one_platform_default",
            "Another platform default Kafka connection was set at the same moment. Reload and choose again.");
        REFUSALS = Collections.unmodifiableMap(refusals);
    }

    private RaceRefusals() {
    }

    /** The refusal for a failure that is one of these races, found anywhere in its cause chain. */
    public static Optional<ResponseDto> refusalFor(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) {
                continue;
            }
            for (Map.Entry<String, String> refusal : REFUSALS.entrySet()) {
                if (message.contains("\"" + refusal.getKey() + "\"") || message.contains("[" + refusal.getKey() + "]")) {
                    return Optional.of(new ResponseDto(ProcessUtil.ERROR_MESSAGE, refusal.getValue()));
                }
            }
        }
        return Optional.empty();
    }

    /** For a controller's catch-all: a lost race is a 409 refusal, anything else the 500 it always was. */
    public static ResponseEntity<ResponseDto> answer(Exception failure, Logger logger, String action) {
        Optional<ResponseDto> refused = refusalFor(failure);
        if (refused.isPresent()) {
            logger.warn("{} lost a concurrent write and was refused: {}", action, refused.get().getMessage());
            return new ResponseEntity<>(refused.get(), HttpStatus.CONFLICT);
        }
        logger.error("An error occurred while {} ", action, failure);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
