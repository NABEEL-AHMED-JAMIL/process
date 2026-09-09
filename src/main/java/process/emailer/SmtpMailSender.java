package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import javax.mail.internet.MimeMessage;

/**
 * The previous transport, kept only as an escape hatch.
 *
 * DEPRECATED. Mail now goes through SES (see SesMailSender); this exists so a deployment that
 * genuinely has its own SMTP relay can opt back in with app.mail.transport=smtp rather than
 * being stranded by the change. It is not the default and should not be used for new work: the
 * configuration it needs is a host, a username and a password, and that password has to live
 * somewhere, which is exactly what moving to the SDK was meant to stop.
 *
 * @author Nabeel Ahmed
 */
@Deprecated
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "smtp")
public class SmtpMailSender implements MailTransport {

    private static final Logger logger = LoggerFactory.getLogger(SmtpMailSender.class);

    private final JavaMailSender javaMailSender;

    public SmtpMailSender(JavaMailSender javaMailSender) {
        this.javaMailSender = javaMailSender;
        logger.warn("Outbound mail transport: SMTP. This path is deprecated -- prefer "
            + "app.mail.transport=ses, which needs no stored credentials.");
    }

    @Override
    public void send(MimeMessage message) {
        this.javaMailSender.send(message);
    }

    @Override
    public String describe() {
        return "SMTP (deprecated)";
    }
}
