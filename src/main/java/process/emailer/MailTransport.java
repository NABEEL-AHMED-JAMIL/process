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

    /**
     * Whether this transport can actually reach a real inbox.
     *
     * False when it is pointed at a local emulator or a capture sandbox. That case is not an
     * error and must not be reported as one -- the message is genuinely built, accepted and
     * stored -- but it is also not delivery, and telling someone "Sent to you@gmail.com" when it
     * went into LocalStack's in-memory outbox sends them to look in an inbox that will never
     * have it. Cost a real debugging session; it is a sentence, so it is said.
     */
    boolean deliversToRealInboxes();
}
