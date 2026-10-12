package process.pipeline.backing;

/**
 * MIG-364: how far a job's read_documents step has read a document type's approved dataset (run_document_read). Each
 * run records the highest dataset row it read; "since the last run" starts after the highest one a COMPLETED run of the
 * job read with the same step and type -- a failed or interrupted run moves nothing, so its documents come again.
 */
public interface DocumentReadMarks {

    /** The highest dataset row a completed run of this job read with this step and type; 0 when none has. */
    long watermark(long jobId, String stepKey, long documentTypeId);

    /** Records what this run's step read: the type, the highest dataset row (or the watermark it started from) and how many rows. */
    void record(long jobQueueId, String stepKey, long jobId, long documentTypeId, long upTo, long rows, int attempt);
}
