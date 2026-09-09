package process.emailer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.LookupDataCacheService;

import javax.mail.internet.MimeMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who a job's notification is addressed to.
 *
 * It used to be whatever single address sat in the EMAIL_RECEIVER lookup: one row, tenant_id
 * NULL, platform-wide. Every tenant's job names and failure messages went to that one mailbox,
 * and the person who owned the job was told nothing. Both halves of that are fixed by resolving
 * the recipient from the job itself, and these pin it.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class JobNotificationRecipientTest {

    @Mock private VelocityManager velocityManager;
    @Mock private LookupDataCacheService lookupDataCacheService;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private MailTransport mailTransport;

    private EmailMessagesFactory factory;

    @BeforeEach
    void setUp() {
        // A real JavaMailSender, unconfigured: it is only used to BUILD the MimeMessage now,
        // never to send one, so it needs no host.
        JavaMailSenderImpl builder = new JavaMailSenderImpl();
        this.factory = new EmailMessagesFactory(builder, this.velocityManager,
            this.lookupDataCacheService, this.sourceJobRepository, this.mailTransport);
        ReflectionTestUtils.setField(this.factory, "sender", "etl@platform.local");
        lenient().when(this.velocityManager.getResponseMessage(any(), any())).thenReturn("<p>body</p>");
        lenient().when(this.mailTransport.describe()).thenReturn("SES test");
    }

    private SourceJobQueueDto jobQueue(Long jobId) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(jobId);
        dto.setJobQueueId(500L);
        dto.setJobName("CSV join demo job");
        return dto;
    }

    @Test
    void addressesTheNotificationToTheJobsOwnAssignedUser() throws Exception {
        when(this.sourceJobRepository.findNotificationRecipient(2407L))
            .thenReturn("owner@acme.test");

        this.factory.sendSourceJobEmail(jobQueue(2407L), JobStatus.Completed);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(this.mailTransport).send(sent.capture());
        assertThat(sent.getValue().getAllRecipients()).hasSize(1);
        assertThat(sent.getValue().getAllRecipients()[0].toString()).isEqualTo("owner@acme.test");
    }

    @Test
    void sendsNothingWhenTheJobHasNoAssignee() throws Exception {
        when(this.sourceJobRepository.findNotificationRecipient(2407L)).thenReturn(null);

        String result = this.factory.sendSourceJobEmail(jobQueue(2407L), JobStatus.Failed);

        // No fallback address: any mailbox we picked would belong to somebody who did not ask
        // for this job's mail, which is the defect being removed.
        verify(this.mailTransport, never()).send(any());
        assertThat(result).isEqualTo("No recipient for this job");
    }

    @Test
    void treatsABlankAssigneeAsNoAssignee() throws Exception {
        when(this.sourceJobRepository.findNotificationRecipient(2407L)).thenReturn("   ");

        this.factory.sendSourceJobEmail(jobQueue(2407L), JobStatus.Failed);

        verify(this.mailTransport, never()).send(any());
    }

    @Test
    void doesNotQueryForARecipientWhenThereIsNoJobId() throws Exception {
        this.factory.sendSourceJobEmail(jobQueue(null), JobStatus.Skip);

        verify(this.sourceJobRepository, never()).findNotificationRecipient(any());
        verify(this.mailTransport, never()).send(any());
    }

    @Test
    void neverConsultsTheRemovedPlatformWideLookup() throws Exception {
        when(this.sourceJobRepository.findNotificationRecipient(2407L))
            .thenReturn("owner@acme.test");

        this.factory.sendSourceJobEmail(jobQueue(2407L), JobStatus.Completed);

        // The whole point: no single platform-wide address is consulted for a job notification.
        verify(this.lookupDataCacheService, never()).getParentLookupById(any());
    }
}
