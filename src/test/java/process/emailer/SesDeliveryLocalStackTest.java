package process.emailer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.LookupDataCacheService;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Properties;
import java.util.Scanner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The last hop of Notifications' mail path, against a real SES (MIG-162): the template renders,
 * the MIME message is built, SesMailSender hands it to SES, and SES has it -- addressed to the
 * job's owner, from the verified sender, with the attachment intact.
 *
 * Every other mail test stops at a rendered string. This one reads the message back out of
 * LocalStack and parses it as MIME, because the failures that have shipped were in the hops a
 * rendered string cannot see: a From address SES refuses, a message that went nowhere while the
 * log said "sent".
 *
 * Opt-in by nature: it runs only when LocalStack answers on localhost:4566
 * (docker compose --profile aws up -d localstack), and skips otherwise, so the suite still runs
 * on a machine without it.
 */
class SesDeliveryLocalStackTest {

    private static final String LOCALSTACK = "http://localhost:4566";
    private static final String SENDER = "no-reply@etl-console.local";

    static boolean localStackIsUp() {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(LOCALSTACK + "/_localstack/health").openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(2000);
            return connection.getResponseCode() == 200 && read(connection.getInputStream()).contains("\"ses\"");
        } catch (Exception unreachable) {
            return false;
        }
    }

    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private EmailMessagesFactory factory;
    private String marker;

    @BeforeEach
    void setUp() {
        // assumeTrue, not @EnabledIf: that arrived in JUnit 5.7 and this build is on 5.6.
        assumeTrue(localStackIsUp(), "LocalStack with SES is not running on localhost:4566");
        VelocityManager velocity = new VelocityManager();
        velocity.init();
        SesMailSender ses = new SesMailSender("us-east-1", LOCALSTACK, "test", "test");
        this.factory = new EmailMessagesFactory(velocity, mock(LookupDataCacheService.class), this.jobs, ses);
        ReflectionTestUtils.setField(this.factory, "sender", SENDER);
        // Every test finds its own message among whatever else LocalStack is holding.
        this.marker = "edge-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void aFailedJobsMailReachesSesAddressedToTheJobsOwnerAndSaysWhy() throws Exception {
        when(this.jobs.findNotificationRecipient(1985L)).thenReturn("owner@medaxis.example");
        SourceJobQueueDto run = new SourceJobQueueDto();
        run.setJobId(1985L);
        run.setJobQueueId(5065L);
        run.setJobName("Nightly export " + this.marker);
        run.setJobStatusMessage("Could not reach bucket litware-financial.");
        run.setStartTime(LocalDateTime.of(2026, 9, 23, 3, 0));

        this.factory.sendSourceJobEmail(run, JobStatus.Failed);

        MimeMessage delivered = this.deliveredContaining(this.marker);
        assertThat(delivered.getSubject()).isEqualTo("Source Job Failed");
        assertThat(delivered.getFrom()[0].toString()).isEqualTo(SENDER);
        assertThat(delivered.getAllRecipients()[0].toString()).isEqualTo("owner@medaxis.example");
        String body = text(delivered);
        assertThat(body).contains("Nightly export " + this.marker).contains("Could not reach bucket litware-financial.");
        assertThat(body).doesNotContain("$request");
    }

    @Test
    void aSharedFileArrivesWithItsAttachmentIntact() throws Exception {
        byte[] file = ("id,region\n1,north\n" + this.marker).getBytes(StandardCharsets.UTF_8);

        this.factory.sendFileShareEmail("colleague@medaxis.example", "Ada King", "orders.csv", "File",
            false, "24 B", "Q3 " + this.marker, file, "orders.csv", "text/csv");

        MimeMessage delivered = this.deliveredContaining(this.marker);
        assertThat(delivered.getSubject()).isEqualTo("Ada King shared \"orders.csv\" with you");
        assertThat(delivered.getAllRecipients()[0].toString()).isEqualTo("colleague@medaxis.example");
        Part attachment = attachment(delivered);
        assertThat(attachment.getFileName()).isEqualTo("orders.csv");
        assertThat(read(attachment.getInputStream())).isEqualTo(new String(file, StandardCharsets.UTF_8));
    }

    /** A job with no owner sends nothing at all -- there is nobody who asked for its mail. */
    @Test
    void aJobWithNoOwnerSendsNothing() throws Exception {
        SourceJobQueueDto run = new SourceJobQueueDto();
        run.setJobId(1986L);
        run.setJobName("Unowned " + this.marker);

        assertThat(this.factory.sendSourceJobEmail(run, JobStatus.Failed)).isEqualTo("No recipient for this job");
        assertThat(this.findContaining(this.marker)).isNull();
    }

    // ---- reading SES back out of LocalStack ------------------------------------------------------

    private MimeMessage deliveredContaining(String marker) throws Exception {
        MimeMessage found = this.findContaining(marker);
        assertThat(found).as("a message containing %s in LocalStack's SES store", marker).isNotNull();
        return found;
    }

    private MimeMessage findContaining(String marker) throws Exception {
        JsonArray messages = JsonParser.parseString(read(new URL(LOCALSTACK + "/_aws/ses").openStream()))
            .getAsJsonObject().getAsJsonArray("messages");
        for (JsonElement element : messages) {
            JsonObject message = element.getAsJsonObject();
            if (!message.has("RawData") || message.get("RawData").isJsonNull()) continue;
            MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(message.get("RawData").getAsString().getBytes(StandardCharsets.UTF_8)));
            if (text(mime).contains(marker) || (attachmentOrNull(mime) != null
                && read(attachmentOrNull(mime).getInputStream()).contains(marker))) {
                return mime;
            }
        }
        return null;
    }

    private static String text(Part part) throws Exception {
        if (part.isMimeType("text/*") && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return String.valueOf(part.getContent());
        }
        if (part.isMimeType("multipart/*")) {
            StringBuilder all = new StringBuilder();
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) all.append(text(multipart.getBodyPart(i)));
            return all.toString();
        }
        return "";
    }

    private static Part attachment(Part part) throws Exception {
        Part found = attachmentOrNull(part);
        assertThat(found).as("an attachment part").isNotNull();
        return found;
    }

    private static Part attachmentOrNull(Part part) throws Exception {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) return part;
        if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart child = multipart.getBodyPart(i);
                Part found = attachmentOrNull(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String read(InputStream in) {
        try (Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}
