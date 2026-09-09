package process.emailer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * caught and logged, and the health indicator for mail is switched off. Moving to the SDK
 * removes the credential pair entirely on deployed environments (the instance's own IAM role
 * signs the request) and, just as usefully, makes the path testable: LocalStack implements SES,
 * so a developer can send a real notification and read it back instead of trusting a log line.
 *
 * SendRawEmail, not SendEmail: the raw form takes the MIME message we already build, so
 * attachments, CC and the HTML body survive the move untouched. SendEmail would have meant
 * re-expressing all of that in SES's own request shape.
 *
 * @author Nabeel Ahmed
 */
@Component
@ConditionalOnProperty(name = "app.mail.transport", havingValue = "ses", matchIfMissing = true)
public class SesMailSender implements MailTransport {

    private static final Logger logger = LoggerFactory.getLogger(SesMailSender.class);

    private final SesClient client;
    private final String endpointDescription;

    public SesMailSender(
        @Value("${app.mail.ses.region:us-east-1}") String region,
        /*
         * Empty on a real deployment, where the default endpoint and the instance's IAM role
         * apply. Set to LocalStack's address for local work, which is the whole reason this is
         * configurable rather than fixed.
         */
        @Value("${app.mail.ses.endpoint:}") String endpoint,
        @Value("${app.mail.ses.access-key:}") String accessKey,
        @Value("${app.mail.ses.secret-key:}") String secretKey) {

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
        this.endpointDescription = (endpoint == null || endpoint.trim().isEmpty())
            ? "SES " + region : "SES " + region + " at " + endpoint.trim();
        logger.info("Outbound mail transport: {}", this.endpointDescription);
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
    public String describe() {
        return this.endpointDescription;
    }

    @PreDestroy
    public void close() {
        this.client.close();
    }
}
