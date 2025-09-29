package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.service.NotifyService;
import process.socket.NotificationService;
import java.util.Optional;
import static process.util.ProcessUtil.SUCCESS;

/**
 * @author Nabeel Ahmed
 */
@Service
public class NotifyServiceImpl implements NotifyService {

    private Logger logger = LoggerFactory.getLogger(NotifyServiceImpl.class);

    private final BulkAction bulkAction;
    private final EmailMessagesFactory emailMessagesFactory;
    private final JobQueueRepository jobQueueRepository;

    public NotifyServiceImpl(
        BulkAction bulkAction,
        EmailMessagesFactory emailMessagesFactory,
        JobQueueRepository jobQueueRepository) {
        this.bulkAction = bulkAction;
        this.emailMessagesFactory = emailMessagesFactory;
        this.jobQueueRepository = jobQueueRepository;
    }

    /**
     * Method use to send email
     * @param sourceJobQueueDto
     * @return ResponseDto
     * */
    @Override
    public ResponseDto sendEmail(SourceJobQueueDto sourceJobQueueDto) {
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(sourceJobQueueDto.getJobQueueId());
        if (jobQueue.isPresent()) {
            sourceJobQueueDto.setJobId(jobQueue.get().getJobId());
            sourceJobQueueDto.setJobQueueId(jobQueue.get().getJobQueueId());
            sourceJobQueueDto.setJobStatus(jobQueue.get().getJobStatus());
            this.emailMessagesFactory.sendSourceJobEmail(sourceJobQueueDto, sourceJobQueueDto.getJobStatus());
        }
        return new ResponseDto(SUCCESS, "Email send.");
    }

    /**
     * Method use to send push notification
     * @return ResponseDto
     * */
    @Override
    public ResponseDto sendJobStatusNotification(Long jobId) {
        this.bulkAction.sendJobStatusNotification(jobId);
        return new ResponseDto(SUCCESS, "Notification send.");
    }

}
