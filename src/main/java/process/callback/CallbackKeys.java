package process.callback;

import process.model.enums.JobStatus;

import java.util.regex.Pattern;

/**
 * Which idempotency key a worker callback is deduplicated on (MIG-33).
 *
 * A key the worker sends in {@value #HEADER} always wins: it is the worker saying "this is the same
 * request as before", and only the worker knows that. Without one, a terminal state change gets a key
 * derived from the run: its status and the attempt its token was minted for. A run can end only once
 * per attempt, so a second Completed or Failed for the same attempt is by definition a redelivery.
 *
 * Running gets no derived key, and that is the heartbeat exemption: Running to Running is how a worker
 * says it is still alive, each one is a new report, and deriving a key from (run, attempt, status)
 * would collapse every heartbeat after the first into a replay and let a healthy long run look
 * stalled. A worker that does send a key on a heartbeat is deduplicated on it, because then a repeat
 * really is a retransmission of the same heartbeat rather than a new one. Log lines are the same: two
 * identical lines are often two real lines, so they are only deduplicated on a key the worker sent.
 *
 * @author Nabeel Ahmed
 */
public final class CallbackKeys {

    public static final String HEADER = "Idempotency-Key";

    /** It is written to the log, so a newline or a kilobyte of anything is refused. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

    private CallbackKeys() {
    }

    public static boolean isAcceptable(String key) {
        return key != null && ACCEPTABLE.matcher(key).matches();
    }

    /** The key a state change without a worker key is deduplicated on; null for a heartbeat. */
    public static String derived(JobStatus status, int attempt) {
        if (status != JobStatus.Completed && status != JobStatus.Failed) {
            return null;
        }
        return "derived:" + status.name() + "#" + Math.max(1, attempt);
    }

    /** What a key was first used for, so one key cannot answer two different callbacks. */
    public static String changeState(JobStatus status) {
        return "changeState:" + status;
    }

    public static final String ADD_LOGS = "addLogs";

    public static final String ADD_LOGS_BATCH = "addLogsBatch";
}
