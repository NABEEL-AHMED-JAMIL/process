package process.notifications;

import org.barco.notifications.contract.MailRequested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Core's half of job mail: recipient, wording and the outcome it announces. */
class JobMailTest {

    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final NotificationPort port = mock(NotificationPort.class);
    private final JobMail jobMail = new JobMail(this.jobs, this.runs, this.port);

    private static SourceJobQueueDto run() {
        SourceJobQueueDto run = new SourceJobQueueDto();
        run.setJobId(41L);
        run.setJobQueueId(7001L);
        run.setJobName("Nightly export");
        run.setJobStatusMessage("Could not reach the bucket.");
        return run;
    }

    @Test
    void aFailedRunsMailGoesToTheJobsOwnerAndNamesItsOutcome() {
        when(this.jobs.findNotificationRecipient(41L)).thenReturn("owner@medaxis.example");
        JobQueue attemptTwo = new JobQueue();
        attemptTwo.setAttempt(2);
        when(this.runs.findById(7001L)).thenReturn(Optional.of(attemptTwo));

        this.jobMail.send(run(), JobStatus.Failed);

        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        verify(this.port).mailRequested(any(), mail.capture(), eq(MailExtras.NONE));
        assertThat(mail.getValue().getTemplate()).isEqualTo(MailRequested.Template.FAIL_JOB);
        assertThat(mail.getValue().getSubject()).isEqualTo("Source Job Failed");
        assertThat(mail.getValue().getRecipient()).isEqualTo("owner@medaxis.example");
        assertThat(mail.getValue().getBodyMap()).containsEntry("job_name", "Nightly export")
            .containsEntry("status_message", "Could not reach the bucket.").containsEntry("event_id", 7001L);
        assertThat(mail.getValue().getDedupeKey()).isEqualTo("run:7001:attempt:2:Failed");
    }

    /** No fallback address: V30.0 removed the platform-wide mailbox that leaked across tenants. */
    @Test
    void aJobWithNoOwnerSendsNothing() {
        assertThat(this.jobMail.send(run(), JobStatus.Completed)).isEqualTo(JobMail.NO_RECIPIENT);
        verify(this.port, never()).mailRequested(any(), any(), any());
    }

    @Test
    void theTemplatesMatchTheStatuses() {
        when(this.jobs.findNotificationRecipient(41L)).thenReturn("owner@medaxis.example");
        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        this.jobMail.send(run(), JobStatus.Skip);
        this.jobMail.send(run(), JobStatus.Completed);
        verify(this.port, org.mockito.Mockito.times(2)).mailRequested(any(), mail.capture(), any());
        assertThat(mail.getAllValues()).extracting(MailRequested::getTemplate)
            .containsExactly(MailRequested.Template.SKIP_JOB, MailRequested.Template.COMPLETE_JOB);
    }
}
