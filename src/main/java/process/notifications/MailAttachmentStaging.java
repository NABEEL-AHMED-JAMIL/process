package process.notifications;

import process.util.BusinessTime;
import org.barco.notifications.contract.MailRequested;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stages a mail attachment where Notifications can fetch it (MIG-22 part 4). Up to 20 MiB does not
 * belong on a topic, so the file goes to the platform's mail-attachments bucket and the event
 * carries {bucket, key, filename, contentType, sizeBytes}. Notifications deletes it once sent; a
 * lifecycle rule on the bucket catches anything a failure leaves behind.
 *
 * @author Nabeel Ahmed
 */
@Component
public class MailAttachmentStaging {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final Supplier<S3Client> s3;
    private final String bucket;

    @Autowired
    public MailAttachmentStaging(ObjectProvider<S3Client> s3, @Value("${app.mail-attachments.bucket:etl-mail-attachments}") String bucket) {
        this(s3::getObject, bucket);
    }

    MailAttachmentStaging(Supplier<S3Client> s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public MailRequested.AttachmentRef stage(byte[] bytes, String filename, String contentType) {
        // The sender's text names the file; it must not name the path.
        String name = filename == null ? "attachment" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        if (name.isEmpty() || name.equals("..") || name.equals(".")) name = "attachment";
        String key = BusinessTime.today().format(DAY) + "/" + UUID.randomUUID() + "/" + name;
        PutObjectRequest put = PutObjectRequest.builder().bucket(this.bucket).key(key).contentType(contentType).build();
        try {
            this.s3.get().putObject(put, RequestBody.fromBytes(bytes));
        } catch (NoSuchBucketException missing) {
            // First share on a fresh environment: the bucket is platform infrastructure, made on demand.
            this.s3.get().createBucket(CreateBucketRequest.builder().bucket(this.bucket).build());
            this.s3.get().putObject(put, RequestBody.fromBytes(bytes));
        }
        return new MailRequested.AttachmentRef().setBucket(this.bucket).setKey(key).setFilename(name)
            .setContentType(contentType).setSizeBytes((long) bytes.length);
    }
}
