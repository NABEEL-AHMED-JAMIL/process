package process.pipeline;

import java.util.List;

/**
 * MIG-360: the files a run was started for, when it took more than one -- a batch of inbox files that waited for their
 * job (inbox_arrival names the run on each). The step engine asks once per run, and only for a run a file started.
 */
public interface RunInputs {

    /** The run's files' keys, in the order they arrived; empty when no inbox file names the run. */
    List<String> keysOf(long jobQueueId);
}
