package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
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
 */
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

    /**
     * method using to send the mail
     * @param jobQueue
     * @param jobStatus
     * @return String
     * */
    public String sendSourceJobEmail(SourceJobQueueDto jobQueue, JobStatus jobStatus) {
        try {
            LookupDataResponse senderEmail = this.lookupDataCacheService
                .getParentLookupById(LookupDetailUtil.EMAIL_SENDER);
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("username", signUpRequest.getUsername());
            metaData.put("password", signUpRequest.getPassword());
            metaData.put("role", signUpRequest.getRole());
            metaData.put("timeZone", signUpRequest.getTimeZone());
            // email object
            EmailMessageRequest emailMessageRequest = new EmailMessageRequest();
            emailMessageRequest.setFromEmail(senderEmail.getLookupValue());
            emailMessageRequest.setRecipients(signUpRequest.getEmail());
            emailMessageRequest.setSubject("User Registered");
            emailMessageRequest.setEmailTemplateName(TemplateType.REGISTER_USER);
            emailMessageRequest.setBodyMap(metaData);
            logger.info("Email Send Status :- " + this.sendSimpleMail(emailMessageRequest));
            return true;
        } catch (Exception ex) {
            logger.error("Exception :- : {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

    /**
     * method using to send the mail
     * @param emailContent
     * @return String
     * */
    private String sendSimpleMail(EmailMessageDto emailContent) {
        try {
            MimeMessage mailMessage = this.javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mailMessage, UTF8);
            helper.setFrom(emailContent.getFromEmail());
            if (!isNull(emailContent.getRecipients())) {
                helper.setTo(emailContent.getRecipients());
                if (emailContent.getRecipientsMulti() != null && !emailContent.getRecipientsMulti().isEmpty()) {
                    // * * * * * * * * *Send cc's* * * * * * * * *
                    String ccSendTo = emailContent.getRecipientsMulti().toString();
                    ccSendTo = ccSendTo.substring(1, ccSendTo.length()-1);
                    helper.setCc(ccSendTo);
                }
                helper.setSubject(emailContent.getSubject());
                helper.setText(this.velocityManager.getResponseMessage(
                    emailContent.getEmailTemplateName(), emailContent.getBodyMap()), true);
                this.javaMailSender.send(mailMessage);
                logger.info("Email Send Successfully Content :- {}.", emailContent.getBodyMap().toString());
            } else {
                logger.error("Error :- Sent To Null Content :- {}.", emailContent.getBodyMap().toString());
            }
            return "Mail Sent Successfully...";
        } catch (Exception ex) {
            logger.error("Exception :- : {}.", ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }
}
