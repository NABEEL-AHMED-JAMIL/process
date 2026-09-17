package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;
import software.amazon.awssdk.services.ses.model.RawMessage;
import software.amazon.awssdk.services.ses.model.SendRawEmailRequest;

import javax.annotation.PreDestroy;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URI;

/**
 * Outbound mail over the SES API rather than SMTP.
 *
 * SMTP was pointed at a Mailtrap sandbox whose credentials no longer authenticate, so nothing
 * had actually been delivered for some time and the failure was invisible -- send errors are
 * caught and logged. Moving to the SDK removes the credential pair entirely on deployed
 * environments (the instance's own IAM role signs the request) and, just as usefully, makes the
 * path testable: LocalStack implements SES, so a developer can send a real notification and read
 * it back instead of trusting a log line. The SMTP transport was kept for a while as an opt-in
 * escape hatch and is gone now: nothing used it, and the host, username and password it needed
 * were four more variables in every environment for a path that was never switched on.
 *
 * SendRawEmail, not SendEmail: the raw form takes the MIME message we already build, so
 * attachments, CC and the HTML body survive the move untouched. SendEmail would have meant
 * re-expressing all of that in SES's own request shape.
 *
 * @author Nabeel Ahmed
 */
@Component
public class SesMailSender implements MailTransport {

    private static final Logger logger = LoggerFactory.getLogger(SesMailSender.class);

    private final SesClient client;
    private final String endpointDescription;
    private final boolean emulated;

    public SesMailSender(
        /*
         * The one AWS identity the platform holds, shared with the platform bucket on S3: mail
         * and pictures are both things the platform itself does, and two key pairs for one
         * account were two things to rotate and two places to leak.
         */
        @Value("${aws.region:us-east-1}") String region,
        /*
         * Empty on a real deployment, where the default endpoint and the instance's IAM role
         * apply. Set to LocalStack's address for local work, which is the whole reason this is
         * configurable rather than fixed.
         */
        @Value("${aws.endpoint:}") String endpoint,
        @Value("${aws.access-key:}") String accessKey,
        @Value("${aws.secret-key:}") String secretKey) {

        SesClientBuilder builder = SesClient.builder().region(Region.of(region));
        if (endpoint != null && !endpoint.trim().isEmpty()) {
            builder = builder.endpointOverride(URI.create(endpoint.trim()));
        }
        // Only when supplied. Otherwise the default provider chain runs, which is what picks up
        // the IAM role in a deployed environment -- credentials never appear in configuration.
        if (accessKey != null && !accessKey.trim().isEmpty()) {
            builder = builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey.trim(), secretKey == null ? "" : secretKey.trim())));
        }
        this.client = builder.build();
        /*
         * An overridden endpoint is, in this project, always a local emulator -- the comment on
         * the constructor argument says as much, and a real deployment leaves it empty so the
         * SDK resolves the regional endpoint itself. LocalStack ACCEPTS a SendRawEmail and stores
         * it in memory; it never delivers. So an override is exactly the signal that "sent"
         * should not be reported to a person as though it had arrived.
         */
        this.emulated = endpoint != null && !endpoint.trim().isEmpty();
        this.endpointDescription = this.emulated
            ? "SES " + region + " at " + endpoint.trim() : "SES " + region;
        logger.info("Outbound mail transport: {}{}", this.endpointDescription,
            this.emulated ? " -- a local emulator: messages are stored, NOT delivered" : "");
    }

    @Override
    public void send(MimeMessage message) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        message.writeTo(raw);
        this.client.sendRawEmail(SendRawEmailRequest.builder()
            .rawMessage(RawMessage.builder()
                .data(SdkBytes.fromByteArray(raw.toByteArray()))
                .build())
            .build());
    }

    @Override
    public boolean deliversToRealInboxes() {
        return !this.emulated;
    }

    @Override
    public String describe() {
        return this.endpointDescription;
    }

    @PreDestroy
    public void close() {
        this.client.close();
    }
}
