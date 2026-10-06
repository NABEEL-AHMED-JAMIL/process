package process.correlation;

import org.barco.platform.correlation.CorrelationId;
import process.model.pojo.JobQueue;

/**
 * Which id a run is dispatched, worked and called back under (MIG-94, after MIG-95).
 *
 * A run made by a request -- Run now, Skip next -- takes that request's id, so the id the console was answered
 * with finds the dispatch, the Kafka record, the worker, every callback and the notification. A run the scheduler
 * makes has no request behind it: it is left without one, and dispatch mints one per run (a tick's id shared by
 * every run it enqueued would tie unrelated runs together). A run that already has an id keeps it: a retry is the
 * same piece of work.
 *
 * @author Nabeel Ahmed
 */
public final class RunCorrelation {

    private RunCorrelation() {
    }

    /** Stamps the current request's id on a run being made, when there is one and the run has none. */
    public static void stamp(JobQueue run) {
        if (run.getCorrelationId() == null && CorrelationId.isAcceptable(CorrelationId.current())) {
            run.setCorrelationId(CorrelationId.current());
        }
    }
}
