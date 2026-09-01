package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.service.StorageBrowserService;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Transcribing from a bucket obeys the same rule as reading from one.
 *
 * The worker that does the transcription holds the platform's own MinIO credentials, so a bucket
 * and key handed to it are fetched with no reference to who asked -- another tenant's bucket,
 * another user's avatar, a key the object browser would refuse. Which is why the object is read
 * on this side now, through the same guarded download, and only its bytes travel onward. These
 * are the cases that must never reach the worker at all.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AudioTranscriptBucketGuardTest {

    private static final String BUCKET = "etl-avatar";
    private static final String KEY = "9/profile/interview.mp3";

    @Mock
    private StorageBrowserService storageBrowserService;

    private AudioTranscriptServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AudioTranscriptServiceImpl(this.storageBrowserService);
    }

    private AudioExtractBucketRequestDto request(String bucket, String key) {
        AudioExtractBucketRequestDto request = new AudioExtractBucketRequestDto();
        request.setBucket(bucket);
        request.setKey(key);
        request.setTimestamps(Boolean.FALSE);
        return request;
    }

    /** Refuses to be read at all: any test that reaches the bytes has already gone too far. */
    private InputStream unreadable() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("this object should never have been read");
            }
        };
    }

    @Test
    void anObjectTheCallerCouldNotDownloadIsNotTranscribedEither() throws Exception {
        when(this.storageBrowserService.downloadObject(BUCKET, KEY, null, null))
            .thenThrow(new IllegalArgumentException("Unknown bucket: " + BUCKET + "."));

        ResponseDto response = this.service.extractFromBucket(this.request(BUCKET, KEY));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("Unknown bucket: " + BUCKET + ".");
        assertThat(response.getData()).isNull();
    }

    @Test
    void theGuardIsAskedAboutTheBucketAndKeyTheCallerActuallyNamed() throws Exception {
        when(this.storageBrowserService.downloadObject(BUCKET, KEY, null, null))
            .thenThrow(new IllegalArgumentException("Unknown bucket: " + BUCKET + "."));

        this.service.extractFromBucket(this.request("  " + BUCKET + "  ", "  " + KEY + "  "));

        // Trimmed, and otherwise exactly as given -- a guard asked about a different pair than the
        // one that would be transcribed would be answering a question nobody asked.
        verify(this.storageBrowserService).downloadObject(BUCKET, KEY, null, null);
    }

    @Test
    void aKeyThatIsNotAudioNeverReachesStorage() throws Exception {
        ResponseDto response = this.service.extractFromBucket(
            this.request(BUCKET, "kafka-secrets/2024/truststore.p12"));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void aMissingBucketOrKeyIsRefusedBeforeAnythingIsResolved() throws Exception {
        assertThat(this.service.extractFromBucket(this.request("  ", KEY)).getStatus()).isEqualTo("ERROR");
        assertThat(this.service.extractFromBucket(this.request(BUCKET, "  ")).getStatus()).isEqualTo("ERROR");
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void anObjectOverTheCeilingIsRefusedBeforeItIsSpooledToDisk() throws Exception {
        long sixHundredMb = 600L * 1024L * 1024L;
        when(this.storageBrowserService.downloadObject(BUCKET, KEY, null, null))
            .thenReturn(new ObjectContentDto(this.unreadable(), "audio/mpeg", sixHundredMb, "interview.mp3"));

        ResponseDto response = this.service.extractFromBucket(this.request(BUCKET, KEY));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("transcription limit");
    }

}
