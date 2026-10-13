package process.model.service;

import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;

/**
 * @author Nabeel Ahmed
 * */
public interface MessageQService {

    ResponseDto fetchLogs(MessageQSearchDto messageQSearch);

    /** UX review 2026-10-13 (B3): the Runs explorer's facets and histogram over fetchLogs' runs. */
    ResponseDto runFacets(MessageQSearchDto messageQSearch);

    ResponseDto failJobLogs(Long jobQId);

    ResponseDto interruptJobLogs(Long jobQId);

    ResponseDto changeJobStatus(QueueMessageStatusDto queueMessageStatus);

}
