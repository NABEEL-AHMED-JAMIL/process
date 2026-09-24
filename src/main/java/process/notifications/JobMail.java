package process.notifications;

import process.identity.IdentityPort;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;

import java.util.Optional;

/**
 * The job-mail half of Core: who a job's mail goes to, what it says, and which outcome it announces.
 *
 * Moved out of EmailMessagesFactory.sendSourceJobEmail, unchanged in what it decides, because those
 * are Core's decisions -- the recipient is the job's assignee (findAssignedUserId), their address
 * Identity's answer through the port, and whether to mail at all is the job's own switch, read by each
 * caller. Notifications only renders and sends. A deleted assignee, or one Identity does not know, is
 * no recipient -- as the join on app_user with status <> 'Delete' decided before MIG-107.
 *
 * A job with no assignee sends nothing. There is deliberately no fallback address: the previous one
 * sent every tenant's job names and failure messages to one platform-wide mailbox (removed in V30.0).
 *
 * @author Nabeel Ahmed
 */
@Component
public class JobMail {

    private static final Logger logger = LoggerFactory.getLogger(JobMail.class);

    public static final String NO_RECIPIENT = "No recipient for this job";

    private final SourceJobRepository jobs;
    private final JobQueueRepository runs;
    private final NotificationPort notifications;
    private final IdentityPort identity;

    public JobMail(SourceJobRepository jobs, JobQueueRepository runs, NotificationPort notifications, IdentityPort identity) {
        this.jobs = jobs;
        this.runs = runs;
        this.notifications = notifications;
        this.identity = identity;
    }

    /** Sends the Completed, Failed or Skip mail for one run. Returns the mailer's answer. */
    public String send(SourceJobQueueDto run, JobStatus status) {
        try {
            String recipient = run.getJobId() == null ? null : this.recipientOf(run.getJobId());
            if (recipient == null || recipient.trim().isEmpty()) {
                logger.warn("Job {} has no assigned user, so its {} notification was not sent.", run.getJobId(), status);
                return NO_RECIPIENT;
            }
            MailRequested mail = new MailRequested().setRecipient(recipient)
                .put("job_id", run.getJobId())
                .put("event_id", run.getJobQueueId())
                .put("time_slot", run.getStartTime())
                .put("job_name", run.getJobName())
                .put("status_message", run.getJobStatusMessage())
                .put("status", status);
            switch (status) {
                case Skip:
                    mail.setTemplate(MailRequested.Template.SKIP_JOB).setSubject("Source Job Skip");
                    break;
                case Completed:
                    mail.setTemplate(MailRequested.Template.COMPLETE_JOB).setSubject("Source Job Completed");
                    break;
                case Failed:
                    mail.setTemplate(MailRequested.Template.FAIL_JOB).setSubject("Source Job Failed");
                    break;
                default:
                    logger.warn("No mail template for a {} run of job {}.", status, run.getJobId());
                    return "No template for this status";
            }
            if (run.getJobQueueId() != null) {
                // One mail per (run, attempt, status): a worker reporting Failed twice for one attempt
                // gets one failure mail. The same key the outcome notice is guarded by.
                mail.setDedupeKey(new JobStatusChanged().setJobQueueId(run.getJobQueueId())
                    .setAttempt(this.attemptOf(run.getJobQueueId())).setJobRunningStatus(status.name()).outcomeKey());
            }
            return this.notifications.mailRequested(null, mail, MailExtras.NONE);
        } catch (Exception ex) {
            logger.error("Could not send the {} mail for job {}: {}", status, run.getJobId(), ex.getMessage());
            return "Error while Sending Mail";
        }
    }

    /** The assignee's username, if the job has one and they are not deleted. */
    private String recipientOf(Long jobId) {
        Long assignee = this.jobs.findAssignedUserId(jobId);
        return assignee == null ? null : this.identity.person(assignee).filter(person -> !person.isDeleted())
            .map(IdentityPort.Person::getUsername).orElse(null);
    }

    private int attemptOf(Long jobQueueId) {
        Optional<JobQueue> run = this.runs.findById(jobQueueId);
        return run.map(JobQueue::getAttempt).orElse(1);
    }
}
