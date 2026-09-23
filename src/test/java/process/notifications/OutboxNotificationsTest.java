package process.notifications;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.notifications.contract.JobLifecycleChanged;
import org.barco.notifications.contract.JobLogAppended;
import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.barco.notifications.contract.NotificationTopics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.outbox.OutboxWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The port as Notifications' own service will be fed (MIG-22): every call becomes one contract
 * event in the outbox, written in the caller's transaction, keyed as the contract says.
 */
class OutboxNotificationsTest {

    private static final long TENANT = 2905L;

    private final OutboxWriter outbox = mock(OutboxWriter.class);
    private final InProcessNotifications inProcess = mock(InProcessNotifications.class);
    private final OutboxNotifications port = new OutboxNotifications(this.outbox, this.inProcess);
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode written(String topic, String key) throws Exception {
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(this.outbox).write(eq(topic), eq(key), anyString(), event.capture());
        return this.json.readTree(event.getValue());
    }

    @Test
    void aJobStatusGoesOutAsOneEventKeyedByItsRun() throws Exception {
        this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(1)
            .setJobRunningStatus("Failed").setNewTransition(true).setRecipientUserId(10L).setJobName("Nightly export"));

        JsonNode event = this.written(NotificationTopics.JOB_STATUS, "7001");
        assertThat(event.get("eventType").asText()).isEqualTo(NotificationTopics.JOB_STATUS);
        assertThat(event.get("tenantId").asLong()).isEqualTo(TENANT);
        assertThat(event.get("producer").asText()).isEqualTo("process");
        assertThat(event.get("eventId").asText()).hasSize(36);
        assertThat(event.get("occurredAt").asText()).endsWith("Z");
        assertThat(event.at("/payload/jobRunningStatus").asText()).isEqualTo("Failed");
        assertThat(event.at("/payload/isNewTransition").asBoolean()).isTrue();
    }

    /** What the service will do with it: read the event back into the contract's own types. */
    @Test
    void theEventReadsBackIntoTheContractTypes() throws Exception {
        this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(2)
            .setJobRunningStatus("Completed").setNewTransition(true).setRecipientUserId(10L));
        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(this.outbox).write(anyString(), anyString(), anyString(), event.capture());

        org.barco.platform.event.PlatformEvent<JobStatusChanged> read = this.json.readValue(event.getValue(),
            new com.fasterxml.jackson.core.type.TypeReference<org.barco.platform.event.PlatformEvent<JobStatusChanged>>() { });

        assertThat(read.getTenantId()).isEqualTo(TENANT);
        assertThat(read.getPayload().isNewTransition()).isTrue();
        assertThat(read.getPayload().outcomeKey()).isEqualTo("run:7001:attempt:2:Completed");
        assertThat(read.getPayload().validated().raisesOutcome()).isTrue();
    }

    @Test
    void aLogLineALifecycleChangeAndANoticeAreKeyedAsTheContractSays() throws Exception {
        this.port.jobLogAppended(TENANT, new JobLogAppended().setJobId(41L).setJobQueueId(7001L).setLineSeq(3L).setMessage("Read 12 files."));
        this.port.jobLifecycleChanged(TENANT, new JobLifecycleChanged().setJobId(41L).setChange(JobLifecycleChanged.Change.toggled));
        this.port.notificationCreated(TENANT, new NotificationCreated().setAppUserId(10L).setType("TASK_ASSIGNED")
            .setSeverity("INFO").setTitle("Assigned"));

        assertThat(this.written(NotificationTopics.JOB_LOG, "7001").at("/payload/lineSeq").asLong()).isEqualTo(3L);
        assertThat(this.written(NotificationTopics.JOB_LIFECYCLE, "41").at("/payload/change").asText()).isEqualTo("toggled");
        assertThat(this.written(NotificationTopics.NOTIFICATION_CREATED, "10").at("/payload/title").asText()).isEqualTo("Assigned");
    }

    @Test
    void aMailWithNothingOutOfBandGoesThroughTheOutbox() throws Exception {
        MailRequested mail = new MailRequested().setTemplate(MailRequested.Template.FAIL_JOB)
            .setRecipient("Owner@MedAxis.example").setSubject("Source Job Failed").setDedupeKey("run:7001:attempt:1:Failed");

        assertThat(this.port.mailRequested(TENANT, mail, MailExtras.NONE)).isEqualTo(OutboxNotifications.QUEUED);

        assertThat(this.written(NotificationTopics.MAIL_REQUESTED, "owner@medaxis.example").at("/payload/subject").asText())
            .isEqualTo("Source Job Failed");
        verify(this.inProcess, never()).mailRequested(any(), any(), any());
    }

    /** Bytes and a password cannot ride on a topic; until they go by reference (secretRef, attachmentRef) they stay here. */
    @Test
    void aMailCarryingBytesOrASecretIsStillSentFromHere() {
        MailRequested welcome = StandardMails.userWelcome("new.user@medaxis.example", "New User", "Acme",
            "new.user@medaxis.example", "tenant user", "Ada King", "http://localhost:4400/login");
        MailExtras password = MailExtras.secret("Tmp-9f2c!");
        when(this.inProcess.mailRequested(TENANT, welcome, password)).thenReturn("Mail sent successfully.");

        assertThat(this.port.mailRequested(TENANT, welcome, password)).isEqualTo("Mail sent successfully.");
        verify(this.outbox, never()).write(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aPayloadThatBreaksTheContractIsDroppedNotWritten() {
        assertThatCode(() -> this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L)
            .setJobRunningStatus("Failed").setNewTransition(true))).doesNotThrowAnyException();
        verify(this.outbox, never()).write(anyString(), anyString(), anyString(), anyString());
    }

    /** A notice for nobody is not an event at all -- as the in-process path always behaved. */
    @Test
    void aNoticeWithNoRecipientWritesNothing() {
        this.port.notificationCreated(TENANT, new NotificationCreated().setType("TASK_ASSIGNED").setSeverity("INFO").setTitle("x"));
        verify(this.outbox, never()).write(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void theOldConsolesPushAndTheSynchronousQueriesStayWithTheInProcessSide() {
        this.port.legacyOwnerPush("ops@medaxis.example", "{}");
        this.port.forgetRecipient(10L);
        this.port.deliversMailToRealInboxes();

        verify(this.inProcess).legacyOwnerPush("ops@medaxis.example", "{}");
        verify(this.inProcess).forgetRecipient(10L);
        verify(this.inProcess).deliversMailToRealInboxes();
    }
}
