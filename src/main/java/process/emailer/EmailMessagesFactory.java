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

/**
 * @author Nabeel Ahmed
 * */
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
            // Both were on the DTO all along and neither reached the template, so every
            // notification opened with "Your Source Job 1985" and a failure never said why.
            metaData.put("job_name", jobQueue.getJobName());
            metaData.put("status_message", jobQueue.getJobStatusMessage());
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

    /**
     * Tells somebody an account has been made for them.
     *
     * temporaryPassword is null when the administrator chose the password themselves -- then the
     * message carries the username and the sign-in link but no credential, because a password
     * somebody else picked is theirs to pass on however they like.
     */
    public String sendUserWelcomeEmail(String recipientEmail, String fullName,
        String organisationName, String username, String temporaryPassword, String roleLabel,
        String createdByName, String signInUrl) {
        try {
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("full_name", fullName);
            metaData.put("organisation_name", organisationName);
            metaData.put("username", username);
            metaData.put("role_label", roleLabel);
            metaData.put("created_by_name", createdByName);
            metaData.put("sign_in_url", signInUrl);
            if (temporaryPassword != null) {
                metaData.put("temporary_password", temporaryPassword);
            }
            EmailMessageDto emailMessageDto = new EmailMessageDto();
            emailMessageDto.setRecipients(recipientEmail);
            emailMessageDto.setSubject("Your ETL Console account");
            emailMessageDto.setEmailTemplateName(TemplateType.USER_WELCOME);
            emailMessageDto.setBodyMap(metaData);
            return this.sendSimpleMail(emailMessageDto);
        } catch (Exception ex) {
            // No exception message: a mail failure can echo the body, and the body may hold a
            // password.
            logger.error("An exception occurred while sending the user welcome email.");
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
                logger.info("Email sent successfully. Content: {}.", safeToLog(emailContent.getBodyMap()));
            } else {
                logger.error("Error: recipient is null. Content: {}.", safeToLog(emailContent.getBodyMap()));
            }
            return "Mail sent successfully.";
        } catch (Exception ex) {
            logger.error("An exception occurred: {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

    /**
     * A mail body without its secrets.
     *
     * The welcome message carries a working temporary password, and logging the body map whole
     * wrote that password into the container log in plain text -- readable by anyone with
     * docker logs, and kept for as long as the logs are. The credential is meant to exist only
     * in the recipient's inbox.
     *
     * Matching is on the key name so a body added later is covered without anyone remembering
     * to come back here.
     */
    private static String safeToLog(java.util.Map<String, Object> bodyMap) {
        if (bodyMap == null) {
            return "{}";
        }
        java.util.Map<String, Object> safe = new java.util.LinkedHashMap<>();
        bodyMap.forEach((key, value) -> {
            String name = key == null ? "" : key.toLowerCase();
            boolean secret = name.contains("password") || name.contains("token")
                || name.contains("secret") || name.contains("credential") || name.endsWith("key");
            safe.put(key, secret ? "****" : value);
        });
        return safe.toString();
    }
}
