package process.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.notifications.contract.JobEvent;
import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.identity.OneTimeSecrets;
import process.identity.TestIdentity;
import process.model.repository.AppUserRepository;
import process.outbox.OutboxWriter;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * MIG-76: the job events process publishes, pinned by their exact wire strings.
 *
 * Process names a job event with the contract's {@link JobEvent}, never a bare string. What leaves it
 * must still be byte-for-byte what notifications-service and the console read today: the outbox
 * topic and envelope eventType ({@code platform.job.*.v1}), the lifecycle payload's {@code change},
 * and the socket type notifications-service pushes for it. Every string below is written out, not
 * taken from a constant, so a renamed constant fails here.
 */
class JobEventWireTest {

    private static final long TENANT = 2905L;

    /** Each job event -> {topic and eventType, socket type}. The whole set: a new event must be added here. */
    private static final Map<JobEvent, String[]> WIRE = new LinkedHashMap<>();

    static {
        WIRE.put(JobEvent.STATUS, new String[] {"platform.job.status.v1", "job.status"});
        WIRE.put(JobEvent.LOG, new String[] {"platform.job.log.v1", "job.log"});
        WIRE.put(JobEvent.UPDATED, new String[] {"platform.job.lifecycle.v1", "job.updated"});
        WIRE.put(JobEvent.DELETED, new String[] {"platform.job.lifecycle.v1", "job.deleted"});
        WIRE.put(JobEvent.TOGGLED, new String[] {"platform.job.lifecycle.v1", "job.toggled"});
    }

    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final OutboxNotifications port = new OutboxNotifications(this.outbox,
        TestIdentity.over(mock(AppUserRepository.class), null), mock(OneTimeSecrets.class),
        mock(MailAttachmentStaging.class), mock(UnreadBadges.class), "http://host.docker.internal:4566");
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void everyJobEventIsPinned() {
        assertThat(WIRE.keySet()).containsExactly(JobEvent.values());
        WIRE.forEach((event, names) -> {
            assertThat(event.topic()).as(event + " topic").isEqualTo(names[0]);
            assertThat(event.socketType()).as(event + " socket type").isEqualTo(names[1]);
        });
    }

    @Test
    void aStatusGoesOutOnItsTopicUnderItsEventType() throws Exception {
        this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(1)
            .setJobRunningStatus("Running"));

        this.assertPublished("platform.job.status.v1");
    }

    @Test
    void aLogLineGoesOutOnItsTopicUnderItsEventType() throws Exception {
        this.port.jobLogAppended(TENANT, new JobLogAppended().setJobId(41L).setJobQueueId(7001L).setLineSeq(3L).setMessage("Read 12 files."));

        this.assertPublished("platform.job.log.v1");
    }

    /** One lifecycle topic; the change inside says which, by the name the service turns into the socket type. */
    @Test
    void everyLifecycleChangeGoesOutOnTheLifecycleTopicWithItsOwnChangeValue() throws Exception {
        String[][] expected = {{"updated", "job.updated"}, {"deleted", "job.deleted"}, {"toggled", "job.toggled"}};
        assertThat(JobLifecycleChanged.Change.values()).hasSize(expected.length);
        for (String[] row : expected) {
            JobLifecycleChanged.Change change = JobLifecycleChanged.Change.valueOf(row[0]);
            this.port.jobLifecycleChanged(TENANT, new JobLifecycleChanged().setJobId(41L).setChange(change));

            JsonNode event = this.assertPublished("platform.job.lifecycle.v1");
            assertThat(event.at("/payload/change").asText()).isEqualTo(row[0]);
            assertThat(change.socketType()).isEqualTo(row[1]);
            assertThat(change.event().socketType()).isEqualTo(row[1]);
            clearInvocations(this.outbox);
        }
    }

    private JsonNode assertPublished(String topic) throws Exception {
        ArgumentCaptor<String> writtenTopic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(this.outbox).write(writtenTopic.capture(), anyString(), anyString(), body.capture());
        JsonNode event = this.json.readTree(body.getValue());
        assertThat(writtenTopic.getValue()).isEqualTo(topic);
        assertThat(event.get("eventType").asText()).isEqualTo(topic);
        return event;
    }
}
