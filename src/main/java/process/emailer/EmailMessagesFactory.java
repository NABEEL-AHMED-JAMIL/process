package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.Properties;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 */
@Component
public class EmailMessagesFactory {

    private Logger logger = LoggerFactory.getLogger(EmailMessagesFactory.class);

    private final String MAIL_SMTP_HOST = "mail.smtp.host";
    private final String SMTP_GMAIL_COM = "smtp.gmail.com";
    private final String MAIL_SMTP_SOCKET_FACTORY_PORT = "mail.smtp.socketFactory.port";
    private final String MAIL_SMTP_SOCKET_FACTORY_CLASS = "mail.smtp.socketFactory.class";
    private final String MAIL_PORT = "465";
    private final String MAIL_SMTP_AUTH = "mail.smtp.auth";
    private final String MAIL_SAMP_AUTH_TRUE = "true";
    private final String SSL_SOCKET_FACTORU = "javax.net.ssl.SSLSocketFactory";
    private final String MAIL_SMTP_PORT = "mail.smtp.port";
    private final String USER_NAME = "nabeel.amd93@gmail.com";
    private final String PASSWORD = "B@llistic1";
    private final String UTF = "text/html; charset=utf-8";

    @Autowired
    private VelocityManager velocityManager;

    private Session getSession() {
        Session session = Session.getInstance(getProperties(),
            new Authenticator() {
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(USER_NAME, PASSWORD);
                }
            });
        return session;
    }

    private Properties getProperties() {
        Properties props = new Properties();
        props.put(MAIL_SMTP_HOST, SMTP_GMAIL_COM);
        props.put(MAIL_SMTP_SOCKET_FACTORY_PORT, MAIL_PORT);
        props.put(MAIL_SMTP_SOCKET_FACTORY_CLASS, SSL_SOCKET_FACTORU);
        props.put(MAIL_SMTP_AUTH, MAIL_SAMP_AUTH_TRUE);
        props.put(MAIL_SMTP_PORT, MAIL_PORT);
        return props;
    }

    /* * * * * * * * * * * * * * * * * * * * *
     * Note :- if email set not proved then  *
     * * * * * * * * * * * * * * * * * * * * */
    public boolean sendMail(EmailMessageDto emailContent) {
        try {
            Message message = new MimeMessage(getSession());
            message.setFrom(new InternetAddress(emailContent.getFromEmail()));
            if(!isNull(emailContent.getRecipients())) {
                // * * * * * * * * * * *Send to* * * * * * * * *
                String sendTo = emailContent.getRecipients();
                logger.info("Send To :- "  + sendTo);
                // * * * * * * * * * * * * * * * * * * * * * * * *
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(sendTo));
                if (emailContent.getRecipientsMulti() != null && emailContent.getRecipientsMulti().size() > 0) {
                    // * * * * * * * * *Send cc's* * * * * * * * *
                    String ccSendTo = emailContent.getRecipientsMulti().toString();
                    ccSendTo = ccSendTo.substring(1, ccSendTo.length()-1);
                    logger.info("Send Cc :- "  + ccSendTo);
                    // * * * * * * * * * * * * * * * * * * * * * * *
                    message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccSendTo));
                }
                message.setSubject(emailContent.getSubject());
                message.setContent(this.velocityManager.getResponseMessage(
                    emailContent.getEmailTemplateName(), emailContent.getBodyMap()), UTF);
                Transport.send(message);
                logger.info("Email Send Successfully.");
                return true;
            } else {
                logger.error("Error :- Sent To Null");
                return false;
            }
        } catch (MessagingException ex) {
            logger.error("Error :- " +  ex + " Use this link to " +
                "Enable https://myaccount.google.com/u/1/lesssecureapps");
            return false;
        }catch (Exception ex) {
            logger.error("Error :- " +  ex);
            return false;
        }
    }

}
