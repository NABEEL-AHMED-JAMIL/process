package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;

/**
 * @author Nabeel Ahmed
 */
public interface SourceJobApiService {

    public ResponseDto addSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto updateSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto deleteSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto runSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto skipNextSourceJob(SourceJobDto tempSourceJob) throws Exception;

    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobDetailId) throws Exception;

    public ResponseDto listSourceJob() throws Exception;

    public ResponseDto fetchRunningJobEvent(SourceJobDto tempSourceJob) throws Exception;

}
