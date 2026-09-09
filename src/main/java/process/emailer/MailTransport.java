package process.emailer;

import javax.mail.internet.MimeMessage;

/**
 * How a built message actually leaves the building.
 *
 * The MIME message itself is still assembled with JavaMail's MimeMessageHelper -- it handles
 * HTML bodies, CC lists, UTF-8 and attachments correctly and there is no reason to reimplement
 * that. Only the transport is behind this interface, so swapping SMTP for SES changed nothing
 * about what the emails contain.
 *
 * @author Nabeel Ahmed
 */
public interface MailTransport {

    void send(MimeMessage message) throws Exception;

    /** For logs and health output: which transport is actually in use. */
    String describe();
}
