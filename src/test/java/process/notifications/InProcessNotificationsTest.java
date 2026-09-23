package process.notifications;

import org.barco.notifications.contract.JobStatusChanged;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import process.emailer.EmailMessagesFactory;
import process.emailer.TemplateType;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.service.NotificationCenterService;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The port's in-process implementation: what it adds over the direct calls it replaced. */
class InProcessNotificationsTest {

    private static final long TENANT = 2905L;

    private final JobEventPublisher jobEvents = mock(JobEventPublisher.class);
    private final NotificationService userPush = mock(NotificationService.class);
    private final NotificationCenterService centre = mock(NotificationCenterService.class);
    private final EmailMessagesFactory mailer = mock(EmailMessagesFactory.class);
    @SuppressWarnings("unchecked")
    private final RedisTemplate<String, String> redis = mock(RedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    /** A Redis that remembers: setIfAbsent is true only the first time a key is seen. */
    private final Set<String> seen = new HashSet<>();
    private InProcessNotifications port;

    @BeforeEach
    void setUp() {
        when(this.redis.opsForValue()).thenReturn(this.values);
        when(this.values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .thenAnswer(inv -> this.seen.add(inv.getArgument(0)));
        when(this.mailer.sendTemplate(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn("Mail sent successfully.");
        this.port = new InProcessNotifications(this.jobEvents, this.userPush, this.centre, this.mailer, this.redis);
    }

    private static JobStatusChanged failed() {
        return new JobStatusChanged().setJobId(41L).setJobQueueId(7001L).setAttempt(1).setJobRunningStatus("Failed")
            .setNewTransition(true).setJobName("Nightly export").setRecipientUserId(10L).setRecipientUsername("ops@medaxis.example");
    }

    /** A worker that reports Failed twice for one attempt raised two notices; it now raises one. */
    @Test
    void aReplayedFailureRaisesOneNotice() {
        this.port.jobStatusChanged(TENANT, failed());
        this.port.jobStatusChanged(TENANT, failed());

        verify(this.centre, times(1)).create(eq(TENANT), eq(10L), eq(NotificationType.JOB_FAILED),
            eq(NotificationSeverity.ERROR), eq("Job failed"), eq("Nightly export failed."), eq("/jobList"));
        // Every copy still reaches the live feed: a repeated push is harmless.
        verify(this.jobEvents, times(2)).publishStatusAfterCommit(TENANT, 41L, 7001L, "Failed", null);
    }

    @Test
    void theNextAttemptsFailureIsItsOwnOutcome() {
        this.port.jobStatusChanged(TENANT, failed());
        this.port.jobStatusChanged(TENANT, failed().setAttempt(2));

        verify(this.centre, times(2)).create(any(), any(), any(), any(), any(), any(), any());
    }

    /** One outcome raises a notice AND a mail under the same key; neither may use up the other. */
    @Test
    void theNoticeAndTheMailForOneOutcomeAreGuardedSeparately() {
        this.port.jobStatusChanged(TENANT, failed());
        MailRequested mail = new MailRequested().setTemplate(MailRequested.Template.FAIL_JOB)
            .setRecipient("ops@medaxis.example").setSubject("Source Job Failed").setDedupeKey(failed().outcomeKey());

        assertThat(this.port.mailRequested(TENANT, mail, MailExtras.NONE)).isEqualTo("Mail sent successfully.");
        assertThat(this.port.mailRequested(TENANT, mail, MailExtras.NONE)).isEqualTo("Mail already sent.");
        verify(this.mailer, times(1)).sendTemplate(eq(TemplateType.FAIL_JOB), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aHeartbeatOrASkipReachesTheFeedAndRaisesNothing() {
        this.port.jobStatusChanged(TENANT, failed().setJobRunningStatus("Running").setNewTransition(false));
        this.port.jobStatusChanged(TENANT, failed().setJobRunningStatus("Completed").setNewTransition(false));

        verify(this.jobEvents, times(2)).publishStatusAfterCommit(eq(TENANT), eq(41L), eq(7001L), anyString(), isNull());
        verify(this.centre, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    /** Contract rule: no credential in an event. The password joins the body only as the mail is rendered. */
    @Test
    void aWelcomeMailsPasswordIsAddedAtRenderTimeNotCarriedInTheEvent() {
        MailRequested welcome = StandardMails.userWelcome("new.user@medaxis.example", "New User", "Acme",
            "new.user@medaxis.example", "tenant user", "Ada King", "http://localhost:4400/login");

        this.port.mailRequested(TENANT, welcome, MailExtras.secret("Tmp-9f2c!"));

        assertThat(welcome.getBodyMap()).doesNotContainKey("temporary_password");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(this.mailer).sendTemplate(eq(TemplateType.USER_WELCOME), eq("new.user@medaxis.example"), any(),
            eq("Your ETL Console account"), body.capture(), isNull(), isNull(), isNull());
        assertThat(body.getValue()).containsEntry("temporary_password", "Tmp-9f2c!").containsEntry("full_name", "New User");
        assertThat(MailExtras.secret("Tmp-9f2c!").toString()).doesNotContain("Tmp-9f2c!");
    }

    @Test
    void aFileShareCarriesItsBytesToTheMailer() {
        byte[] zip = {1, 2, 3};
        this.port.mailRequested(TENANT, StandardMails.fileShare("colleague@medaxis.example", "Ada King", "q3", "Folder",
            true, "3 B (1 file)", "fyi", "q3.zip", "application/zip", zip.length), MailExtras.attachment(zip));

        verify(this.mailer).sendTemplate(eq(TemplateType.FILE_SHARE), eq("colleague@medaxis.example"), any(),
            eq("Ada King shared \"q3\" with you"), any(), eq(zip), eq("q3.zip"), eq("application/zip"));
    }

    /** A push nobody receives must never fail the operation that triggered it. */
    @Test
    void aPayloadThatBreaksTheContractIsDroppedNotThrown() {
        assertThatCode(() -> this.port.jobStatusChanged(TENANT, failed().setJobQueueId(null))).doesNotThrowAnyException();
        assertThatCode(() -> this.port.notificationCreated(TENANT, new NotificationCreated().setAppUserId(10L)
            .setType("NO_SUCH_TYPE").setSeverity("INFO").setTitle("x"))).doesNotThrowAnyException();
        assertThat(this.port.mailRequested(TENANT, new MailRequested().setTemplate(MailRequested.Template.USER_WELCOME)
            .setRecipient("new.user@medaxis.example").put("password", "leaked"), MailExtras.NONE))
            .isEqualTo("Error while Sending Mail");
        verify(this.centre, never()).create(any(), any(), any(), any(), any(), any(), any());
        verify(this.mailer, never()).sendTemplate(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aNoticeWithNoRecipientIsQuietlyNotSent() {
        this.port.notificationCreated(TENANT, new NotificationCreated().setType("TASK_ASSIGNED").setSeverity("INFO").setTitle("x"));
        verify(this.centre, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    /** Fails open: a rare duplicate is better than a failure nobody hears about. */
    @Test
    void withRedisDownTheNoticeStillGoesOut() {
        when(this.values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenThrow(new IllegalStateException("redis down"));

        this.port.jobStatusChanged(TENANT, failed());

        verify(this.centre).create(any(), eq(10L), eq(NotificationType.JOB_FAILED), any(), any(), any(), any());
    }
}
