package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.pojo.AppUser;
import process.model.pojo.Notification;
import process.model.repository.AppUserRepository;
import process.model.repository.NotificationRepository;
import process.security.TenantContext;
import process.util.ProcessUtil;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two ways the notification centre used to lie.
 *
 * markRead answered SUCCESS whatever the update touched, so a wrong id, another tenant's id and a
 * real mark-as-read were one indistinguishable response. And the Redis unread counter was written
 * but never removed, which is why clearUnreadCount exists -- together with the reason a TTL could
 * not stand in for it: create() rebuilt a missing key with INCR, so an evicted key came back as
 * "1 unread" rather than as a recount.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceImplTest {

    private static final long TENANT_ID = 1001L;
    private static final long ME = 3468L;
    private static final long SOMEONE_ELSE = 2992L;
    private static final long NOTIFICATION_ID = 77L;
    private static final String MY_UNREAD_KEY = "notif:unread:" + ME;

    @Mock
    private NotificationRepository notificationRepository;
    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private RedisTemplate<String, String> redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private NotificationCenterServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotificationCenterServiceImpl(this.notificationRepository,
            this.appUserRepository, this.redisTemplate, this.messagingTemplate);
        lenient().when(this.redisTemplate.opsForValue()).thenReturn(this.valueOperations);
        TenantContext.set(TENANT_ID, "TENANT_USER", ME, "me@etl.test");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Notification notificationFor(Long recipientUserId, boolean read) {
        Notification notification = new Notification();
        notification.setNotificationId(NOTIFICATION_ID);
        notification.setTenantId(TENANT_ID);
        notification.setRecipientUserId(recipientUserId);
        notification.setType(NotificationType.JOB_COMPLETED);
        notification.setSeverity(NotificationSeverity.SUCCESS);
        notification.setTitle("Job finished");
        notification.setRead(read);
        notification.setDateCreated(LocalDateTime.now());
        return notification;
    }

    @Test
    void markReadReportsAnIdThatDoesNotExistAsNotFound() throws Exception {
        when(this.notificationRepository.markRead(eq(NOTIFICATION_ID), any(), eq(ME))).thenReturn(0);
        when(this.notificationRepository.findById(NOTIFICATION_ID)).thenReturn(Optional.empty());

        ResponseDto response = this.service.markRead(NOTIFICATION_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("Notification not found with 77.");
        // Nothing was marked, so the badge must not move.
        verify(this.valueOperations, never()).decrement(MY_UNREAD_KEY);
    }

    @Test
    void markReadReportsAnotherUsersNotificationAsNotFoundRatherThanForbidden() throws Exception {
        when(this.notificationRepository.markRead(eq(NOTIFICATION_ID), any(), eq(ME))).thenReturn(0);
        when(this.notificationRepository.findById(NOTIFICATION_ID))
            .thenReturn(Optional.of(notificationFor(SOMEONE_ELSE, false)));

        ResponseDto response = this.service.markRead(NOTIFICATION_ID);

        // Same answer as a missing row on purpose: a distinct "not yours" would confirm the id
        // exists to a caller who cannot see it.
        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("Notification not found with 77.");
        verify(this.valueOperations, never()).decrement(MY_UNREAD_KEY);
    }

    @Test
    void markReadTreatsMyOwnAlreadyReadNotificationAsANoOpNotAFailure() throws Exception {
        when(this.notificationRepository.markRead(eq(NOTIFICATION_ID), any(), eq(ME))).thenReturn(0);
        when(this.notificationRepository.findById(NOTIFICATION_ID))
            .thenReturn(Optional.of(notificationFor(ME, true)));

        ResponseDto response = this.service.markRead(NOTIFICATION_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).isEqualTo("Notification already marked as read.");
        // Distinct from a real mark: decrementing again would push the badge below the truth.
        verify(this.valueOperations, never()).decrement(MY_UNREAD_KEY);
    }

    @Test
    void markReadReportsSuccessAndDropsTheBadgeWhenARowIsActuallyMarked() throws Exception {
        when(this.notificationRepository.markRead(eq(NOTIFICATION_ID), any(), eq(ME))).thenReturn(1);
        when(this.valueOperations.decrement(MY_UNREAD_KEY)).thenReturn(47L);

        ResponseDto response = this.service.markRead(NOTIFICATION_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).isEqualTo("Marked as read.");
        verify(this.valueOperations).decrement(MY_UNREAD_KEY);
        // 47 is a legitimate count, so it must be left alone rather than clamped.
        verify(this.valueOperations, never()).set(MY_UNREAD_KEY, "0");
        verify(this.notificationRepository, never()).findById(anyLong());
    }

    @Test
    void markReadClampsABadgeThatDecrementedBelowZero() throws Exception {
        when(this.notificationRepository.markRead(eq(NOTIFICATION_ID), any(), eq(ME))).thenReturn(1);
        when(this.valueOperations.decrement(MY_UNREAD_KEY)).thenReturn(-1L);

        ResponseDto response = this.service.markRead(NOTIFICATION_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.valueOperations).set(MY_UNREAD_KEY, "0");
    }

    @Test
    void markReadRejectsAMissingId() throws Exception {
        ResponseDto response = this.service.markRead(null);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("notificationId missing.");
        verify(this.notificationRepository, never()).markRead(any(), any(), any());
    }

    @Test
    void clearUnreadCountRemovesTheKeyLeftBehindByADeletedUser() {
        this.service.clearUnreadCount(SOMEONE_ELSE);

        verify(this.redisTemplate).delete("notif:unread:" + SOMEONE_ELSE);
    }

    @Test
    void clearUnreadCountIgnoresAMissingUserIdRatherThanDeletingTheWholePrefix() {
        this.service.clearUnreadCount(null);

        // "notif:unread:null" is not a key anyone owns, but reaching Redis at all here would mean
        // the caller's null had been silently turned into a plausible-looking key name.
        verify(this.redisTemplate, never()).delete(any(String.class));
    }

    @Test
    void createRecountsFromTheDatabaseWhenTheCachedBadgeIsGone() {
        // The regression clearUnreadCount would otherwise have introduced: INCR materialises a
        // missing key at 1, so a reactivated user with 48 older unread rows would have been told
        // they had 1.
        when(this.redisTemplate.hasKey(MY_UNREAD_KEY)).thenReturn(false);
        when(this.notificationRepository.countByRecipientUserIdAndReadFalse(ME)).thenReturn(49L);
        when(this.valueOperations.setIfAbsent(MY_UNREAD_KEY, "49")).thenReturn(true);
        when(this.appUserRepository.findById(ME)).thenReturn(Optional.of(recipient()));

        this.service.create(TENANT_ID, ME, NotificationType.JOB_COMPLETED,
            NotificationSeverity.SUCCESS, "Job finished", "Pipeline ok", "/job/1");

        verify(this.valueOperations, never()).increment(MY_UNREAD_KEY);
        assertThat(pushedPayload()).contains("\"unreadCount\":49");
    }

    @Test
    void createIncrementsTheCachedBadgeWhenTheKeyIsStillThere() {
        when(this.redisTemplate.hasKey(MY_UNREAD_KEY)).thenReturn(true);
        when(this.valueOperations.increment(MY_UNREAD_KEY)).thenReturn(49L);
        when(this.appUserRepository.findById(ME)).thenReturn(Optional.of(recipient()));

        this.service.create(TENANT_ID, ME, NotificationType.JOB_COMPLETED,
            NotificationSeverity.SUCCESS, "Job finished", "Pipeline ok", "/job/1");

        // The common path must stay a single INCR -- no database count per notification.
        verify(this.notificationRepository, never()).countByRecipientUserIdAndReadFalse(anyLong());
        assertThat(pushedPayload()).contains("\"unreadCount\":49");
    }

    @Test
    void createDefersToAConcurrentSeedInsteadOfOverwritingIt() {
        // Two notifications racing on a cold key: the loser of setIfAbsent must increment the
        // winner's value, not replace it, or one of the two drops off the badge.
        when(this.redisTemplate.hasKey(MY_UNREAD_KEY)).thenReturn(false);
        when(this.notificationRepository.countByRecipientUserIdAndReadFalse(ME)).thenReturn(49L);
        when(this.valueOperations.setIfAbsent(MY_UNREAD_KEY, "49")).thenReturn(false);
        when(this.valueOperations.increment(MY_UNREAD_KEY)).thenReturn(50L);
        when(this.appUserRepository.findById(ME)).thenReturn(Optional.of(recipient()));

        this.service.create(TENANT_ID, ME, NotificationType.JOB_COMPLETED,
            NotificationSeverity.SUCCESS, "Job finished", "Pipeline ok", "/job/1");

        verify(this.valueOperations, never()).set(eq(MY_UNREAD_KEY), any());
        assertThat(pushedPayload()).contains("\"unreadCount\":50");
    }

    @Test
    void createWithNoRecipientTouchesNothing() {
        this.service.create(TENANT_ID, null, NotificationType.JOB_COMPLETED,
            NotificationSeverity.SUCCESS, "Job finished", "Pipeline ok", "/job/1");

        verify(this.notificationRepository, never()).saveAndFlush(any());
        verify(this.redisTemplate, never()).hasKey(any(String.class));
    }

    private AppUser recipient() {
        AppUser user = new AppUser();
        user.setAppUserId(ME);
        user.setUsername("me@etl.test");
        return user;
    }

    /** The websocket frame the bell renders its badge from. */
    private String pushedPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(this.messagingTemplate).convertAndSendToUser(eq("me@etl.test"),
            eq("/queue/notifications"), payload.capture());
        return String.valueOf(payload.getValue());
    }

}
