package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import process.payload.request.ForgotPasswordRequest;
import process.payload.request.PasswordResetRequest;
import process.payload.request.SignupRequest;
import process.payload.response.LookupDataResponse;
import process.security.jwt.JwtUtils;
import process.service.LookupDataCacheService;
import process.util.CommonUtil.*;
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

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private JavaMailSender javaMailSender;
    @Autowired
    private VelocityManager velocityManager;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;

    /**
     * sendRegisterUser method use on user register.
     * @param signUpRequest
     * */
    public boolean sendRegisterUser(SignupRequest signUpRequest) {
        try {
            LookupDataResponse senderEmail = this.lookupDataCacheService
                .getParentLookupById(LookupDetail.EMAIL_SENDER);
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
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return false;
        }
    }

    /**
     * sendForgotPassword method use to send forgot password email
     * @param forgotPasswordRequest
     * */
    public boolean sendForgotPassword(ForgotPasswordRequest forgotPasswordRequest) {
        try {
            LookupDataResponse senderEmail = this.lookupDataCacheService
                .getParentLookupById(LookupDetail.EMAIL_SENDER);
            LookupDataResponse resetPasswordLink = this.lookupDataCacheService
                .getParentLookupById(LookupDetail.RESET_PASSWORD_LINK);
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("username", forgotPasswordRequest.getUsername());
            metaData.put("forgotPasswordPageUrl", resetPasswordLink.getLookupValue()+"?token="+
                this.jwtUtils.generateTokenFromUsername(forgotPasswordRequest.toString()));
            // email object
            EmailMessageRequest emailMessageRequest = new EmailMessageRequest();
            emailMessageRequest.setFromEmail(senderEmail.getLookupValue());
            emailMessageRequest.setRecipients(forgotPasswordRequest.getEmail());
            emailMessageRequest.setSubject("Forgot Password");
            emailMessageRequest.setEmailTemplateName(TemplateType.FORGOT_PASS);
            emailMessageRequest.setBodyMap(metaData);
            logger.info("Email Send Status :- " + this.sendSimpleMail(emailMessageRequest));
            return true;
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return false;
        }
    }

    /**
     * sendResetPassword method use to send reset confirm email
     * @param passwordResetRequest
     * */
    public boolean sendResetPassword(PasswordResetRequest passwordResetRequest) {
        try {
            LookupDataResponse senderEmail = this.lookupDataCacheService
                .getParentLookupById(LookupDetail.EMAIL_SENDER);
            Map<String, Object> metaData = new HashMap<>();
            metaData.put("username", passwordResetRequest.getUsername());
            // email object
            EmailMessageRequest emailMessageRequest = new EmailMessageRequest();
            emailMessageRequest.setFromEmail(senderEmail.getLookupValue());
            emailMessageRequest.setRecipients(passwordResetRequest.getEmail());
            emailMessageRequest.setSubject("Password Updated");
            emailMessageRequest.setEmailTemplateName(TemplateType.RESET_PASS);
            emailMessageRequest.setBodyMap(metaData);
            logger.info("Email Send Status :- " + this.sendSimpleMail(emailMessageRequest));
            return true;
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return false;
        }
    }

    /**
     * sendSimpleMail method use to send email.
     * @param emailContent
     * */
    private String sendSimpleMail(EmailMessageRequest emailContent) {
        try {
            MimeMessage mailMessage = this.javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mailMessage, UTF8);
            helper.setFrom(emailContent.getFromEmail());
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
                //logger.info(String.format("Email Send Successfully Content %s.", emailContent.getBodyMap().toString()));
            } else {
                logger.error(String.format("Error :- Sent To Null Content %s.", emailContent.getBodyMap().toString()));
            }
            return "Mail Sent Successfully...";
        } catch (Exception ex) {
            logger.error("Exception :- " + ExceptionUtil.getRootCauseMessage(ex));
            return "Error while Sending Mail";
        }
    }

}
