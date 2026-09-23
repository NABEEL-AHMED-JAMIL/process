package process.media.share;

import org.barco.notifications.contract.MailRequested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.model.dto.ResponseDto;
import process.model.service.StorageBrowserService;
import process.notifications.MailExtras;
import process.notifications.NotificationPort;
import process.notifications.OutboxNotifications;
import process.security.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A share is sent asynchronously once Notifications is its own service (MIG-22 part 4): the reply
 * says it is queued rather than sent, and the request carries what to tell the sender if it fails.
 */
class FileShareAsyncMailTest {

    private final NotificationPort notifications = mock(NotificationPort.class);
    private final FileShareServiceImpl service = new FileShareServiceImpl(mock(StorageBrowserService.class), this.notifications);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aQueuedShareSaysQueuedAndCarriesTheSendersFailureNotice() throws Exception {
        TenantContext.set(2905L, "TENANT_USER", 10L, "ops@medaxis.example");
        when(this.notifications.mailRequested(any(), any(), any())).thenReturn(OutboxNotifications.QUEUED);
        when(this.notifications.deliversMailToRealInboxes()).thenReturn(true);

        ResponseDto reply = this.service.emailGeneratedFile("colleague@medaxis.example", "Q3 report", "q3.csv",
            "text/csv", new byte[] {1, 2, 3}, "fyi");

        assertThat(reply.getStatus()).isEqualTo("SUCCESS");
        assertThat(reply.getMessage()).startsWith("Email queued.");
        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        verify(this.notifications).mailRequested(eq(2905L), mail.capture(), any(MailExtras.class));
        assertThat(mail.getValue().getFailureNotice().getAppUserId()).isEqualTo(10L);
        assertThat(mail.getValue().getFailureNotice().getType()).isEqualTo("FILE_SHARE_FAILED");
        assertThat(mail.getValue().getFailureNotice().getBody()).contains("Q3 report").contains("colleague@medaxis.example");
    }

    /** Nobody acting (a system job): no failure notice, rather than one addressed to nobody that would sink the mail. */
    @Test
    void withNobodyActingTheMailGoesWithoutAFailureNotice() throws Exception {
        when(this.notifications.mailRequested(any(), any(), any())).thenReturn("Mail sent successfully.");
        when(this.notifications.deliversMailToRealInboxes()).thenReturn(true);

        this.service.emailGeneratedFile("colleague@medaxis.example", "Q3 report", "q3.csv", "text/csv", new byte[] {1}, null);

        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        verify(this.notifications).mailRequested(any(), mail.capture(), any(MailExtras.class));
        assertThat(mail.getValue().getFailureNotice()).isNull();
        assertThat(mail.getValue().validated()).isNotNull();
    }
}
