package process.notifications;

import org.barco.notifications.contract.MailRequested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** A shared file staged where Notifications can fetch it (MIG-22 part 4). */
class MailAttachmentStagingTest {

    private final S3Client s3 = mock(S3Client.class);
    private final MailAttachmentStaging staging = new MailAttachmentStaging(() -> this.s3, "etl-mail-attachments");

    @Test
    void theFileIsStagedUnderAnUnguessableKeyAndDescribedByReference() {
        MailRequested.AttachmentRef ref = this.staging.stage(new byte[] {1, 2, 3}, "q3 report.zip", "application/zip");

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(this.s3).putObject(put.capture(), any(RequestBody.class));
        assertThat(put.getValue().bucket()).isEqualTo("etl-mail-attachments");
        assertThat(put.getValue().key()).matches("\\d{4}/\\d{2}/\\d{2}/[0-9a-f-]{36}/q3 report\\.zip").isEqualTo(ref.getKey());
        assertThat(put.getValue().contentType()).isEqualTo("application/zip");
        assertThat(ref.getBucket()).isEqualTo("etl-mail-attachments");
        assertThat(ref.getFilename()).isEqualTo("q3 report.zip");
        assertThat(ref.getSizeBytes()).isEqualTo(3L);
        assertThat(ref.validated()).isSameAs(ref);
    }

    /** A filename is the sender's text; it must not be able to climb out of its own folder. */
    @Test
    void aFilenameCannotChooseItsOwnPath() {
        MailRequested.AttachmentRef ref = this.staging.stage(new byte[] {1}, "../../etl-config/kafka-secrets/x.jks", "application/octet-stream");

        assertThat(ref.getKey()).doesNotContain("..").endsWith("/x.jks");
        assertThat(ref.getFilename()).isEqualTo("x.jks");
    }
}
