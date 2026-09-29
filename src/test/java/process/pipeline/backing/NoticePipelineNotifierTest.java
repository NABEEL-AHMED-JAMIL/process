package process.pipeline.backing;

import org.barco.notifications.contract.NotificationCreated;
import org.barco.platform.tenancy.TenantScope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.identity.IdentityPort;
import process.notifications.NotificationPort;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-231: Send Notification's notices go through the outbox, one per recipient, members of the run's workspace only. */
class NoticePipelineNotifierTest {

    private static final long TENANT = 41L;

    private final NotificationPort notifications = mock(NotificationPort.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final NoticePipelineNotifier notifier = new NoticePipelineNotifier(this.notifications, this.identity);

    NoticePipelineNotifierTest() {
        when(this.identity.members(any(TenantScope.class))).thenReturn(Arrays.asList(
            new IdentityPort.Person(1L, TENANT, "admin@acme", "Ada", "TENANT_ADMIN", "Active"),
            new IdentityPort.Person(2L, TENANT, "user@acme", "Bo", "TENANT_USER", "Active"),
            new IdentityPort.Person(3L, TENANT, "gone@acme", "Cy", "TENANT_ADMIN", "Delete"),
            new IdentityPort.Person(9L, 42L, "other@beta", "Di", "TENANT_ADMIN", "Active")));
    }

    private PipelineNotifier.Notice notice(String to) {
        PipelineNotifier.Notice notice = new PipelineNotifier.Notice();
        notice.tenantId = TENANT;
        notice.to = to;
        notice.ownerUserId = 2L;
        notice.userIds = Arrays.asList(2L, 9L);
        notice.severity = "ERROR";
        notice.title = "3 claims failed";
        notice.body = "Run 7401.";
        notice.link = "/jobList";
        return notice;
    }

    @Test
    void adminsAreTheWorkspacesLiveAdmins() {
        assertThat(this.notifier.send(this.notice("admins"))).isEqualTo(1);
        ArgumentCaptor<NotificationCreated> sent = ArgumentCaptor.forClass(NotificationCreated.class);
        verify(this.notifications).notificationCreated(eq(TENANT), sent.capture());
        assertThat(sent.getValue().getAppUserId()).isEqualTo(1L);
        assertThat(sent.getValue().getType()).isEqualTo("JOB_FAILED");
        assertThat(sent.getValue().getSeverity()).isEqualTo("ERROR");
        assertThat(sent.getValue().getTitle()).isEqualTo("3 claims failed");
        assertThat(sent.getValue().getLink()).isEqualTo("/jobList");
    }

    @Test
    void namedPeopleOutsideTheWorkspaceAreSkippedAndEveryoneIsItsLiveMembers() {
        assertThat(this.notifier.send(this.notice("users"))).as("9 is another workspace's").isEqualTo(1);
        assertThat(this.notifier.send(this.notice("everyone"))).isEqualTo(2);
        assertThat(this.notifier.send(this.notice("owner"))).isEqualTo(1);
        verify(this.notifications, times(4)).notificationCreated(eq(TENANT), any(NotificationCreated.class));
    }

    @Test
    void anOwnerWhoIsNotAMemberGetsNothing() {
        PipelineNotifier.Notice notice = this.notice("owner");
        notice.ownerUserId = 9L;
        notice.userIds = Collections.emptyList();
        assertThat(this.notifier.send(notice)).isZero();
        verify(this.notifications, never()).notificationCreated(any(), any());
    }
}
