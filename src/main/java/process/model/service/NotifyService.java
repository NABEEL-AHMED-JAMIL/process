package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;

public interface NotifyService {

    public ResponseDto changeState(SourceJobQueueDto jobQueue);

    public ResponseDto addLogs(SourceJobQueueDto jobQueueDto);

}
