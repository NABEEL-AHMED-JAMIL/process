package process.model.service;

import process.model.dto.ResponseDto;
import java.util.List;
import process.model.dto.SchedulerDto;
import process.model.dto.SourceJobDto;

/**
 * @author Nabeel Ahmed
 * */
public interface SourceJobService {

    ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception;

    /** The next runs an unsaved timetable would make, worked out by the scheduler's own rules; nothing is saved. */
    ResponseDto schedulePreview(SchedulerDto schedulerDto);

    ResponseDto updateSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto deleteSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto toggleSourceJobStatus(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto runSourceJob(SourceJobDto sourceJobDto) throws Exception;

    /**
     * Run now, with a "Run with..." (MIG-242): {@code modelProfiles} is a ModelProfiles value the caller has checked
     * (process.ai.AiModelChoiceService), or null for the run's usual models. Every check Run now makes, it makes.
     */
    ResponseDto runSourceJob(SourceJobDto sourceJobDto, String modelProfiles) throws Exception;

    /**
     * Run now for a workflow step (MIG-273): the job is run as its workspace -- the caller has set it -- with every check
     * Run now makes, and {@code reason} written in the run's audit log. Answers the queued run's id in the data, or the
     * same refusal Run now gives.
     */
    ResponseDto runSourceJobFor(Long jobId, String reason) throws Exception;

    ResponseDto skipNextSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto findSourceJobAuditLog(Long jobQueueIdb, Long jobId) throws Exception;

    ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) throws Exception;

    /** The newest window of a job's runs, at the default size. */
    default ResponseDto fetchSourceJobQueueListWithJobId(Long jobId) throws Exception {
        return this.fetchSourceJobQueueListWithJobId(jobId, null, null);
    }

    /**
     * A job's runs, newest first, a window at a time (scale review P0 #1): {@code limit} runs (default
     * and ceiling in the implementation) older than {@code beforeId} when it is given. The payload keeps
     * {@code jobQueues} and adds {@code hasMore} and {@code limit}.
     */
    ResponseDto fetchSourceJobQueueListWithJobId(Long jobId, Integer limit, Long beforeId) throws Exception;

    /** Every listed job, as the console has always read it. */
    default ResponseDto listSourceJob() throws Exception {
        return this.listSourceJob(null, null, null);
    }

    /**
     * The job list (scale review P1 #20): a page in job id order when {@code size} is given, only the
     * named jobs when {@code jobIds} is (a screen re-reading the rows a push named), else every job.
     */
    ResponseDto listSourceJob(Integer page, Integer size, List<Long> jobIds) throws Exception;

    /** What the signed-in person has been doing: their jobs, and how their recent runs went. */
    ResponseDto fetchMyActivity(int limit, int windowDays) throws Exception;

}
