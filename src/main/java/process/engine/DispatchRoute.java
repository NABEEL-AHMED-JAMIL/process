package process.engine;

import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.util.KafkaTopicPartitionUtil;
import process.util.ProcessUtil;

import java.util.Optional;

import static java.util.Objects.isNull;

/**
 * Where a run's message goes, or why it cannot go anywhere (MIG-134): rows 1 to 4 of the dispatch
 * decision tree, asked the same way by the pre-dispatch phase and again by the dispatcher -- the task
 * can be reconfigured in between.
 *
 * Every refusal here is a configuration fault that no retry clears, and each sentence is the one the
 * run is closed with.
 */
public final class DispatchRoute {

    public final SourceTaskType taskType;
    public final String topic;
    /** Null for "any partition". */
    public final Integer partition;
    public final String refusal;

    private DispatchRoute(SourceTaskType taskType, String topic, Integer partition, String refusal) {
        this.taskType = taskType;
        this.topic = topic;
        this.partition = partition;
        this.refusal = refusal;
    }

    public boolean refused() {
        return this.refusal != null;
    }

    private static DispatchRoute refuse(String refusal) {
        return new DispatchRoute(null, null, null, refusal);
    }

    public static DispatchRoute of(SourceJob sourceJob, Long jobId) {
        SourceTask sourceTask = sourceJob.getTaskDetail();
        if (isNull(sourceTask)) {
            return refuse(String.format("Job %s has no task attached, so there is nothing to dispatch.", jobId));
        }
        SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
        if (isNull(sourceTaskType)) {
            return refuse(String.format("Job %s has no task type configured, so there is no broker to dispatch it to.", jobId));
        }
        if (!Status.Active.equals(sourceTaskType.getStatus())) {
            return refuse(String.format("Broker is not active for job %s.", jobId));
        }
        String queueTopicPartition = sourceTaskType.getQueueTopicPartition();
        Optional<KafkaTopicPartitionUtil.Parsed> parsed = KafkaTopicPartitionUtil.parse(queueTopicPartition);
        if (!parsed.isPresent()) {
            // The configured value is an argument, not part of the format string: it is typed by an
            // operator and a '%' in it would otherwise be read as a conversion.
            return refuse(String.format("Broker configuration is invalid for job %s: %s", jobId, queueTopicPartition));
        }
        String partition = parsed.get().getPartition();
        return new DispatchRoute(sourceTaskType, parsed.get().getTopic(),
            partition.contains(ProcessUtil.START) ? null : Integer.valueOf(partition), null);
    }
}
