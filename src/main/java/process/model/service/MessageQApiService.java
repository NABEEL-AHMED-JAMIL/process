package process.model.service;

import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 */
public interface MessageQApiService {

    public ResponseDto fetchLogs(MessageQSearchDto messageQSearch);

    public ResponseDto failJobLogs(Long jobQId);

    public ResponseDto changeJobStatus(QueueMessageStatusDto queueMessageStatus);

}