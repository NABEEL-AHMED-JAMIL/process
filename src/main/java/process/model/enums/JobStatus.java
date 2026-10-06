package process.model.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * @author Nabeel Ahmed
 * */
public enum JobStatus {
    Queue, Start, Running, Failed, Completed, Skip, Interrupt, Missed;

    /**
     * The statuses in which a run occupies the queue: queued, dispatched to a worker that has not
     * yet reported, or reported as running.
     *
     * This set used to be written out wherever it was needed, and one copy drifted. The dispatcher
     * counted all three when deciding a job was busy, the stall sweep cleared all three, and the
     * queue screen offered its actions on all three -- but "Run now" and "Skip next" checked only
     * Queue and Running. A job dispatched a moment ago, sitting in Start until its worker spoke,
     * could be run again by hand, and two runs of one job wrote into the same output folder.
     *
     * Java callers use this. The two native queries in JobQueueRepository cannot reference it, so
     * JobStatusInFlightTest holds their string lists to it instead -- change the set here and that
     * test says which query still names the old one.
     */
    public static final Set<JobStatus> IN_FLIGHT =
        Collections.unmodifiableSet(EnumSet.of(Queue, Start, Running));

    public boolean isInFlight() {
        return IN_FLIGHT.contains(this);
    }
}
