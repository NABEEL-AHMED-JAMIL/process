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
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.outbox.OutboxWriter;

import java.util.Optional;

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
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final process.identity.OneTimeSecrets secrets = mock(process.identity.OneTimeSecrets.class);
    private final MailAttachmentStaging staging = mock(MailAttachmentStaging.class);
    private final LegacyConsolePush legacyConsole = mock(LegacyConsolePush.class);
    private final UnreadBadges badges = mock(UnreadBadges.class);
    private final OutboxNotifications port = new OutboxNotifications(this.outbox, this.users, this.secrets, this.staging,
        this.legacyConsole, this.badges, "http://host.docker.internal:4566");

    @org.junit.jupiter.api.BeforeEach
    void recipients() {
        when(this.users.findById(10L)).thenReturn(Optional.of(user(10L, "ops@medaxis.example", TENANT)));
    }

    private static AppUser user(long id, String username, Long tenantId) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setUsername(username);
        user.setTenantId(tenantId);
        return user;
    }
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
    }

    /** Part 4: a password never rides on a topic; it goes by a one-time reference Notifications redeems. */
    @Test
    void aWelcomeMailsPasswordTravelsAsAOneTimeReference() throws Exception {
        when(this.secrets.keep("Tmp-9f2c!")).thenReturn("ref-7c1d");
        MailRequested welcome = StandardMails.userWelcome("new.user@medaxis.example", "New User", "Acme",
            "new.user@medaxis.example", "tenant user", "Ada King", "http://localhost:4400/login");

        assertThat(this.port.mailRequested(TENANT, welcome, MailExtras.secret("Tmp-9f2c!"))).isEqualTo(OutboxNotifications.QUEUED);

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        verify(this.outbox).write(eq(NotificationTopics.MAIL_REQUESTED), eq("new.user@medaxis.example"), anyString(), event.capture());
        assertThat(event.getValue()).doesNotContain("Tmp-9f2c!");
        assertThat(this.json.readTree(event.getValue()).at("/payload/secretRef").asText()).isEqualTo("ref-7c1d");
    }

    /** Part 4: twenty megabytes do not belong on a topic; the file is staged and sent by reference. */
    @Test
    void aSharedFileIsStagedAndSentByReference() throws Exception {
        byte[] zip = {1, 2, 3};
        when(this.staging.stage(zip, "q3.zip", "application/zip")).thenReturn(new MailRequested.AttachmentRef()
            .setBucket("etl-mail-attachments").setKey("2026/09/23/abc/q3.zip").setFilename("q3.zip")
            .setContentType("application/zip").setSizeBytes(3L));
        MailRequested share = StandardMails.fileShare("colleague@medaxis.example", "Ada King", "q3", "Folder",
            true, "3 B (1 file)", "fyi", "q3.zip", "application/zip", zip.length);

        assertThat(this.port.mailRequested(TENANT, share, MailExtras.attachment(zip))).isEqualTo(OutboxNotifications.QUEUED);

        JsonNode payload = this.written(NotificationTopics.MAIL_REQUESTED, "colleague@medaxis.example").get("payload");
        assertThat(payload.at("/attachmentRef/key").asText()).isEqualTo("2026/09/23/abc/q3.zip");
        assertThat(payload.at("/attachmentRef/bucket").asText()).isEqualTo("etl-mail-attachments");
    }

    /** The sender hears about a failure later, so Core resolves who they are now (contract 1.3.0 rules). */
    @Test
    void theFailureNoticeGoesToTheSenderAsCoreResolvedThem() throws Exception {
        MailRequested mail = new MailRequested().setTemplate(MailRequested.Template.COMPLETE_JOB).setRecipient("owner@medaxis.example")
            .setSubject("Source Job Completed").setFailureNotice(new NotificationCreated().setAppUserId(10L)
                .setType("FILE_SHARE_FAILED").setSeverity("ERROR").setTitle("Not sent"));

        this.port.mailRequested(TENANT, mail, MailExtras.NONE);

        JsonNode notice = this.written(NotificationTopics.MAIL_REQUESTED, "owner@medaxis.example").at("/payload/failureNotice");
        assertThat(notice.get("recipientUsername").asText()).isEqualTo("ops@medaxis.example");
        assertThat(notice.get("recipientTenantId").asLong()).isEqualTo(TENANT);
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

    /** Part 5: nothing is left in-process. The old console's push rides the bridge; the badge is dropped in Redis. */
    @Test
    void theOldConsolesPushAndTheBadgeNeedNoDeliveryCodeHere() {
        this.port.legacyOwnerPush("ops@medaxis.example", "{}");
        this.port.forgetRecipient(10L);

        verify(this.legacyConsole).toOwner("ops@medaxis.example", "{}");
        verify(this.badges).forget(10L);
    }

    /** With an emulator endpoint set, mail is stored, not delivered -- the file-share reply says so. */
    @Test
    void anEmulatorEndpointMeansNoRealInboxes() {
        assertThat(this.port.deliversMailToRealInboxes()).isFalse();
        assertThat(new OutboxNotifications(this.outbox, this.users, this.secrets, this.staging, this.legacyConsole,
            this.badges, "").deliversMailToRealInboxes()).isTrue();
    }

    // ---- contract 1.2.0: Core resolves the recipient, because the service cannot read app_user ----

    @Test
    void aNoticeIsSentWithItsRecipientsUsernameAndHomeTenant() throws Exception {
        this.port.notificationCreated(null, new NotificationCreated().setAppUserId(10L).setType("TASK_ASSIGNED")
            .setSeverity("INFO").setTitle("Assigned"));

        JsonNode payload = this.written(NotificationTopics.NOTIFICATION_CREATED, "10").get("payload");
        assertThat(payload.get("recipientUsername").asText()).isEqualTo("ops@medaxis.example");
        assertThat(payload.get("recipientTenantId").asLong()).isEqualTo(TENANT);
    }

    @Test
    void aPlatformAdminsNoticeHasNoHomeTenant() throws Exception {
        when(this.users.findById(1000L)).thenReturn(Optional.of(user(1000L, "admin@platform.local", null)));
        this.port.notificationCreated(TENANT, new NotificationCreated().setAppUserId(1000L).setType("USER_ADDED")
            .setSeverity("INFO").setTitle("User added"));

        JsonNode payload = this.written(NotificationTopics.NOTIFICATION_CREATED, "1000").get("payload");
        assertThat(payload.get("recipientUsername").asText()).isEqualTo("admin@platform.local");
        // Contract 1.3.0: the platform scope is written, never left out.
        assertThat(payload.get("recipientTenantId").asLong()).isEqualTo(org.barco.notifications.contract.Recipients.PLATFORM_SCOPE);
    }

    @Test
    void aNoticeForSomeoneWhoNoLongerExistsIsNotAnEvent() {
        this.port.notificationCreated(TENANT, new NotificationCreated().setAppUserId(77L).setType("TASK_ASSIGNED")
            .setSeverity("INFO").setTitle("Assigned"));
        verify(this.outbox, never()).write(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void anOutcomeCarriesItsRecipientsHomeTenant() throws Exception {
        this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(1)
            .setJobRunningStatus("Completed").setNewTransition(true).setRecipientUserId(10L));

        JsonNode payload = this.written(NotificationTopics.JOB_STATUS, "7001").get("payload");
        assertThat(payload.get("recipientUsername").asText()).isEqualTo("ops@medaxis.example");
        assertThat(payload.get("recipientTenantId").asLong()).isEqualTo(TENANT);
    }

    /** The live feed must not lose a status because the job's owner was deleted since. */
    @Test
    void aStatusWhoseRecipientIsGoneStillGoesOutWithoutOne() throws Exception {
        this.port.jobStatusChanged(TENANT, new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(1)
            .setJobRunningStatus("Failed").setNewTransition(true).setRecipientUserId(77L).setRecipientUsername("gone@medaxis.example"));

        JsonNode payload = this.written(NotificationTopics.JOB_STATUS, "7001").get("payload");
        assertThat(payload.get("jobRunningStatus").asText()).isEqualTo("Failed");
        assertThat(payload.has("recipientUserId")).isFalse();
        assertThat(payload.has("recipientUsername")).isFalse();
    }
}
