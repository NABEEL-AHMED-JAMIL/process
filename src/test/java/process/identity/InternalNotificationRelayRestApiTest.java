package process.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.notifications.contract.MailRequested;
import org.barco.notifications.contract.NotificationCreated;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import process.notifications.MailExtras;
import process.notifications.NotificationPort;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-107: Identity's notices and mails reach Notifications through process's outbox, as they did while
 * Identity lived here -- POST /internal/notifications/{notice,mail,forgetRecipient}, service token only.
 * A welcome mail's temporary password travels as MailExtras.secret, sealed by reference by the outbox.
 */
class InternalNotificationRelayRestApiTest {

    private static final String TOKEN = "t0ken";
    private final NotificationPort port = mock(NotificationPort.class);
    private final InternalNotificationRelayRestApi api = new InternalNotificationRelayRestApi(this.port, TOKEN);
    private final ObjectMapper json = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object event) {
        return this.json.convertValue(event, Map.class);
    }

    @Test
    void withoutTheServiceTokenNothingIsRelayed() {
        Map<String, Object> body = Collections.singletonMap("appUserId", 7);
        assertThat(this.api.notice(null, body).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.mail("wrong", body).getStatusCodeValue()).isEqualTo(401);
        assertThat(this.api.forgetRecipient("", body).getStatusCodeValue()).isEqualTo(401);
        verifyNoInteractions(this.port);
    }

    @Test
    void aNoticeReachesTheOutboxForItsTenant() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", 2901);
        body.put("notice", this.asMap(new NotificationCreated().setAppUserId(7L).setType("USER_CREATED").setSeverity("INFO")
            .setTitle("Welcome").setBody("Your account is ready.").setLink("/profile")));

        assertThat(this.api.notice(TOKEN, body).getStatusCodeValue()).isEqualTo(200);

        ArgumentCaptor<NotificationCreated> notice = ArgumentCaptor.forClass(NotificationCreated.class);
        verify(this.port).notificationCreated(eq(2901L), notice.capture());
        assertThat(notice.getValue().getAppUserId()).isEqualTo(7L);
        assertThat(notice.getValue().getTitle()).isEqualTo("Welcome");
        assertThat(notice.getValue().getLink()).isEqualTo("/profile");
    }

    @Test
    void aMailCarriesItsSecretToTheOutboxAndAnswersWhatTheOutboxSaid() {
        when(this.port.mailRequested(any(), any(), any())).thenReturn("Mail queued.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", null);
        body.put("mail", this.asMap(new MailRequested().setTemplate(MailRequested.Template.USER_WELCOME)
            .setRecipient("olivia@a.example").setSubject("Welcome").put("fullName", "Olivia")));
        body.put("secret", "Temp-Pass-1");

        ResponseEntity<?> answer = this.api.mail(TOKEN, body);

        assertThat(answer.getStatusCodeValue()).isEqualTo(200);
        assertThat(((Map<?, ?>) answer.getBody()).get("result")).isEqualTo("Mail queued.");
        ArgumentCaptor<MailRequested> mail = ArgumentCaptor.forClass(MailRequested.class);
        ArgumentCaptor<MailExtras> extras = ArgumentCaptor.forClass(MailExtras.class);
        verify(this.port).mailRequested(eq(null), mail.capture(), extras.capture());
        assertThat(mail.getValue().getTemplate()).isEqualTo(MailRequested.Template.USER_WELCOME);
        assertThat(mail.getValue().getRecipient()).isEqualTo("olivia@a.example");
        assertThat(extras.getValue().getSecret()).isEqualTo("Temp-Pass-1");
    }

    @Test
    void aMailWithoutASecretCarriesNone() {
        when(this.port.mailRequested(any(), any(), any())).thenReturn("Mail queued.");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", 2901);
        body.put("mail", this.asMap(new MailRequested().setTemplate(MailRequested.Template.TENANT_WELCOME)
            .setRecipient("owner@a.example").setSubject("Welcome")));

        this.api.mail(TOKEN, body);

        ArgumentCaptor<MailExtras> extras = ArgumentCaptor.forClass(MailExtras.class);
        verify(this.port).mailRequested(eq(2901L), any(), extras.capture());
        assertThat(extras.getValue().getSecret()).isNull();
    }

    @Test
    void anUnreadableMailIsRefusedInWordsAndNothingIsQueued() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mail", "not a mail");

        ResponseEntity<?> answer = this.api.mail(TOKEN, body);

        assertThat(answer.getStatusCodeValue()).isEqualTo(400);
        assertThat(String.valueOf(((Map<?, ?>) answer.getBody()).get("result"))).startsWith("Error");
        verifyNoInteractions(this.port);
    }

    @Test
    void forgettingARecipientReachesTheOutbox() {
        assertThat(this.api.forgetRecipient(TOKEN, Collections.singletonMap("appUserId", 7)).getStatusCodeValue()).isEqualTo(200);
        verify(this.port).forgetRecipient(7L);
    }
}
