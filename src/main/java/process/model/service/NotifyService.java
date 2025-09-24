package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;

/**
 * @author Nabeel Ahmed
 */
public interface NotifyService {

    public ResponseDto sendEmail(SourceJobQueueDto sourceJobQueueDto);

    public ResponseDto sendJobStatusNotification(Long jobId);

}
