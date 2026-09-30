package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;

/**
 * @author Nabeel Ahmed
 * */
public interface SourceJobService {

    ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto updateSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto deleteSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto toggleSourceJobStatus(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto runSourceJob(SourceJobDto sourceJobDto) throws Exception;

    /**
     * Run now, with a "Run with..." (MIG-242): {@code modelProfiles} is a ModelProfiles value the caller has checked
     * (process.ai.AiModelChoiceService), or null for the run's usual models. Every check Run now makes, it makes.
     */
    ResponseDto runSourceJob(SourceJobDto sourceJobDto, String modelProfiles) throws Exception;

    ResponseDto skipNextSourceJob(SourceJobDto sourceJobDto) throws Exception;

    ResponseDto findSourceJobAuditLog(Long jobQueueIdb, Long jobId) throws Exception;

    ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) throws Exception;

    ResponseDto fetchSourceJobQueueListWithJobId(Long jobId) throws Exception;

    ResponseDto listSourceJob() throws Exception;

    /** What the signed-in person has been doing: their jobs, and how their recent runs went. */
    ResponseDto fetchMyActivity(int limit, int windowDays) throws Exception;

}
