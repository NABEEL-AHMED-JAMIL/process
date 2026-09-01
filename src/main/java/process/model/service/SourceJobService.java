package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;

/**
 * @author Nabeel Ahmed
 * */
public interface SourceJobService {

    public ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto updateSourceJob(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto deleteSourceJob(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto toggleSourceJobStatus(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto runSourceJob(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto skipNextSourceJob(SourceJobDto sourceJobDto) throws Exception;

    public ResponseDto findSourceJobAuditLog(Long jobQueueIdb, Long jobId) throws Exception;

    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) throws Exception;

    public ResponseDto fetchSourceJobQueueListWithJobId(Long jobId) throws Exception;

    public ResponseDto listSourceJob() throws Exception;

    /** What the signed-in person has been doing: their jobs, and how their recent runs went. */
    public ResponseDto fetchMyActivity(int limit, int windowDays) throws Exception;

}
