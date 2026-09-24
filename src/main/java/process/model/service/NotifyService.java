package process.model.service;

import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
public interface NotifyService {

    public ResponseDto changeState(SourceJobQueueDto jobQueue);

    public ResponseDto addLogs(SourceJobQueueDto jobQueueDto);

    public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages);

    /**
     * The worker callbacks, deduplicated on an idempotency key (MIG-18). A null key means the worker
     * sent none: a terminal state change is then keyed on its run and attempt, and anything else is
     * applied every time. See process.callback.CallbackKeys.
     */
    public ResponseDto changeState(SourceJobQueueDto jobQueue, String idempotencyKey);

    public ResponseDto addLogs(SourceJobQueueDto jobQueueDto, String idempotencyKey);

    public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String idempotencyKey);

    /**
     * The answer already given to this callback, if it was given one. For a run that is over, where the
     * callback itself would be refused: a worker re-sending the Completed whose answer it lost is told
     * what it was told the first time, not that it is unauthorised. Writes nothing.
     */
    public Optional<ResponseDto> replay(Long jobQueueId, JobStatus jobStatus, String request, String idempotencyKey);

}
