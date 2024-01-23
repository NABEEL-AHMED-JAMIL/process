package process.model.service;

import process.payload.request.MessageQSearchRequest;
import process.payload.request.QueueMessageStatusRequest;
import process.payload.response.AppResponse;

/**
 * @author Nabeel Ahmed
 */
public interface MessageQApiService {

    public AppResponse fetchLogs(MessageQSearchRequest messageQSearch);

    public AppResponse failJobLogs(Long jobQId);

    public AppResponse interruptJobLogs(Long jobQId);

    public AppResponse changeJobStatus(QueueMessageStatusRequest queueMessageStatus);

}