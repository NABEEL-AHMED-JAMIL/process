package process.pipeline.review;

import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;

import java.util.Map;

/**
 * MIG-334: running a run again after the customer rejects it with rerun -- the customer API's way, as an API client
 * starts a run (process.customer.CustomerReruns), not the console's Run now, which is a person's. The answer is the
 * one {@link RunReviewService} records either way: {queued, jobQueueId, message}; a run that is not queued leaves the
 * rejection standing.
 */
public interface CustomerRunAgain {

    Map<String, Object> runAgain(JobQueue rejected, SourceJob job);
}
