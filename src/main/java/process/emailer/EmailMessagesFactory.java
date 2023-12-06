package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import process.model.service.impl.LookupDataCacheService;
import org.springframework.stereotype.Component;
import process.model.dto.LookupDataDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
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
    @Autowired
    private JavaMailSender javaMailSender;
    @Autowired
    private VelocityManager velocityManager;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;

    /**
     * method using to send the mail
     * @param jobQueue
     * @param jobStatus
     * @return String
     * */
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
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
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
            helper.setFrom(sender);
            if(!isNull(emailContent.getRecipients())) {
                helper.setTo(emailContent.getRecipients());
                if (emailContent.getRecipientsMulti() != null && emailContent.getRecipientsMulti().size() > 0) {
                    // * * * * * * * * *Send cc's* * * * * * * * *
                    String ccSendTo = emailContent.getRecipientsMulti().toString();
                    ccSendTo = ccSendTo.substring(1, ccSendTo.length()-1);
                    helper.setCc(ccSendTo);
                }
                helper.setSubject(emailContent.getSubject());
                helper.setText(this.velocityManager.getResponseMessage(
                    emailContent.getEmailTemplateName(), emailContent.getBodyMap()), true);
                this.javaMailSender.send(mailMessage);
                logger.info(String.format("Email Send Successfully Content %s .", emailContent.getBodyMap().toString()));
            } else {
                logger.error(String.format("Error :- Sent To Null Content %s .", emailContent.getBodyMap().toString()));
            }
            return "Mail Sent Successfully...";
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

    /**
     * method use convert job queue to job dto
     * @param jobQueue
     * @return SourceJobQueueDto
     * */
    public static SourceJobQueueDto getSourceJobQueueDto(JobQueue jobQueue) {
        SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
        sourceJobQueueDto.setJobId(jobQueue.getJobId());
        sourceJobQueueDto.setJobQueueId(jobQueue.getJobQueueId());
        if (jobQueue.getJobStatus().equals(JobStatus.Skip)) {
            sourceJobQueueDto.setStartTime(jobQueue.getSkipTime());
        } else {
            sourceJobQueueDto.setStartTime(jobQueue.getStartTime());
        }
        return sourceJobQueueDto;
    }

}
