package process.callback;

import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.util.ProcessUtil;

/**
 * A worker callback refused because the run is not in a state it may move from (MIG-201; worker-runtime
 * contract section 4 and section 16 items 5 and 6). NotifyResetApi answers it 409 Conflict -- the platform's
 * ErrorCategory.CONFLICT, "the state has moved on since the caller looked" -- with the same envelope body it
 * always had, {"status":"ERROR","message":"Invalid status transition from X to Y"}.
 *
 * The sentence is also the marker. A refused transition is recorded as a callback receipt (MIG-18), which
 * keeps only a status and a message, and a redelivery answered from that receipt must be refused with the
 * same 409; so the one sentence is written here and recognised here, and nowhere else.
 *
 * @author Nabeel Ahmed
 */
public final class TransitionConflict {

    private static final String PREFIX = "Invalid status transition from ";

    private TransitionConflict() {
    }

    /** The refusal NotifyServiceImpl answers with; a run with no status at all is refused the same way. */
    public static ResponseDto refusal(JobStatus from, JobStatus to, Object data) {
        return new ResponseDto(ProcessUtil.ERROR, PREFIX + (from == null ? "no status" : from.name()) + " to " + to, data);
    }

    /** Whether this answer -- first-hand or replayed from its receipt -- is a refused transition. */
    public static boolean is(ResponseDto outcome) {
        return outcome != null && ProcessUtil.ERROR.equals(outcome.getStatus())
            && outcome.getMessage() != null && outcome.getMessage().startsWith(PREFIX);
    }
}
