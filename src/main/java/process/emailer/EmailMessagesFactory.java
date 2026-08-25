package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import process.model.service.impl.LookupDataCacheService;
import org.springframework.stereotype.Component;
import process.model.dto.LookupDataDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import javax.mail.internet.MimeMessage;
import java.util.HashMap;
import java.util.Map;
import static process.util.ProcessUtil.isNull;

@Component
public class EmailMessagesFactory {

    private Logger logger = LoggerFactory.getLogger(EmailMessagesFactory.class);

    private final String UTF8 = "utf-8";

    @Value("${spring.mail.username}")
    private String sender;

    private final JavaMailSender javaMailSender;
    private final VelocityManager velocityManager;
    private final LookupDataCacheService lookupDataCacheService;

    public EmailMessagesFactory(JavaMailSender javaMailSender,
        VelocityManager velocityManager,
        LookupDataCacheService lookupDataCacheService) {
        this.javaMailSender = javaMailSender;
        this.velocityManager = velocityManager;
        this.lookupDataCacheService = lookupDataCacheService;
    }

    public String sendSourceJobEmail(SourceJobQueueDto jobQueue, JobStatus jobStatus) {
        try {
            LookupDataDto lookupDataDto = this.lookupDataCacheService.getParentLookupById(ProcessUtil.EMAIL_RECEIVER);
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("job_id", jobQueue.getJobId());
            metaData.put("event_id", jobQueue.getJobQueueId());
            metaData.put("time_slot", jobQueue.getStartTime());
            EmailMessageDto emailMessageDto = new EmailMessageDto();
            emailMessageDto.setRecipients(lookupDataDto.getLookupValue());
            if (jobStatus.equals(JobStatus.Skip)) {
                metaData.put("status", JobStatus.Skip);
                emailMessageDto.setSubject("Source Job Skip");
                emailMessageDto.setEmailTemplateName(TemplateType.SKIP_JOB);
            } else if (jobStatus.equals(JobStatus.Completed)) {
                metaData.put("status", JobStatus.Completed);
                emailMessageDto.setSubject("Source Job Completed");
                emailMessageDto.setEmailTemplateName(TemplateType.COMPLETE_JOB);
            } else if (jobStatus.equals(JobStatus.Failed)) {
                metaData.put("status", JobStatus.Failed);
                emailMessageDto.setSubject("Source Job Failed");
                emailMessageDto.setEmailTemplateName(TemplateType.FAIL_JOB);
            }
            emailMessageDto.setBodyMap(metaData);
            return this.sendSimpleMail(emailMessageDto);
        } catch (Exception ex) {
            logger.error("An exception occurred: {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

    public String sendFileShareEmail(String recipientEmail, String senderName, String itemName, String itemType,
        boolean zipped, String sizeLabel, String message, byte[] attachmentBytes, String attachmentFilename, String attachmentContentType) {
        try {
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("sender_name", senderName);
            metaData.put("item_name", itemName);
            metaData.put("item_type", itemType);
            metaData.put("item_label", ("Folder".equals(itemType) ? "a folder" : "Selection".equals(itemType) ? "a selection" : "a file"));
            metaData.put("size_label", sizeLabel);
            metaData.put("message", message);
            metaData.put("attachment_note", zipped
                ? "It's attached below as a ZIP file."
                : "It's attached below.");
            EmailMessageDto emailMessageDto = new EmailMessageDto();
            emailMessageDto.setRecipients(recipientEmail);
            emailMessageDto.setSubject(senderName + " shared \"" + itemName + "\" with you");
            emailMessageDto.setEmailTemplateName(TemplateType.FILE_SHARE);
            emailMessageDto.setBodyMap(metaData);
            emailMessageDto.setAttachmentBytes(attachmentBytes);
            emailMessageDto.setAttachmentFilename(attachmentFilename);
            emailMessageDto.setAttachmentContentType(attachmentContentType);
            return this.sendSimpleMail(emailMessageDto);
        } catch (Exception ex) {
            logger.error("An exception occurred: {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

    /**
     * The one message that carries a credential.
     *
     * The password is passed in and used once, here; it is never logged and never returned, so
     * the only place it exists in readable form is the message itself. Whoever approved the
     * request does not see it either.
     */
    public String sendTenantWelcomeEmail(String recipientEmail, String contactName,
        String organisationName, String username, String temporaryPassword, String signInUrl) {
        try {
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("contact_name", contactName);
            metaData.put("organisation_name", organisationName);
            metaData.put("username", username);
            metaData.put("temporary_password", temporaryPassword);
            metaData.put("sign_in_url", signInUrl);
            EmailMessageDto emailMessageDto = new EmailMessageDto();
            emailMessageDto.setRecipients(recipientEmail);
            emailMessageDto.setSubject("Your " + organisationName + " workspace is ready");
            emailMessageDto.setEmailTemplateName(TemplateType.TENANT_WELCOME);
            emailMessageDto.setBodyMap(metaData);
            return this.sendSimpleMail(emailMessageDto);
        } catch (Exception ex) {
            // Deliberately does not include the exception's message: a mail failure can echo the
            // message body, and this body holds the password.
            logger.error("An exception occurred while sending the tenant welcome email.");
            return "Error while Sending Mail";
        }
    }

    private String sendSimpleMail(EmailMessageDto emailContent) {
        try {
            MimeMessage mailMessage = this.javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mailMessage, emailContent.getAttachmentBytes() != null, UTF8);
            helper.setFrom(sender);
            if(!isNull(emailContent.getRecipients())) {
                helper.setTo(emailContent.getRecipients());
                if (emailContent.getRecipientsMulti() != null && !emailContent.getRecipientsMulti().isEmpty()) {

                    String ccSendTo = emailContent.getRecipientsMulti().toString();
                    ccSendTo = ccSendTo.substring(1, ccSendTo.length()-1);
                    helper.setCc(ccSendTo);
                }
                helper.setSubject(emailContent.getSubject());
                String message = this.velocityManager.getResponseMessage(emailContent.getEmailTemplateName(), emailContent.getBodyMap());
                helper.setText(message, true);
                if (emailContent.getAttachmentBytes() != null) {
                    helper.addAttachment(emailContent.getAttachmentFilename(),
                        new ByteArrayResource(emailContent.getAttachmentBytes()), emailContent.getAttachmentContentType());
                }
                this.javaMailSender.send(mailMessage);
                logger.info("Email sent successfully. Content: {}.", emailContent.getBodyMap().toString());
            } else {
                logger.error("Error: recipient is null. Content: {}.", emailContent.getBodyMap().toString());
            }
            return "Mail sent successfully.";
        } catch (Exception ex) {
            logger.error("An exception occurred: {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }
}
