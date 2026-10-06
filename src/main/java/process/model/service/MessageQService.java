package process.model.service;

import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface MessageQService {

    ResponseDto fetchLogs(MessageQSearchDto messageQSearch);

    ResponseDto failJobLogs(Long jobQId);

    ResponseDto interruptJobLogs(Long jobQId);

    ResponseDto changeJobStatus(QueueMessageStatusDto queueMessageStatus);

}
