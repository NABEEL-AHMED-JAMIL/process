package process.util;

import com.azure.core.http.HttpResponse;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The parts of StorageNotFound the provider-shape suite does not pin (MIG-122): Azure's 404, and the
 * exact cap on the cause-chain walk. The cap is what stops a pathological chain from hanging a
 * request; depth 12 is still read, depth 13 is not.
 */
class StorageNotFoundDepthTest {

    /** A missing object wrapped the way every adapter wraps it, n times over. */
    private static Throwable wrapped(Throwable cause, int wrappers) {
        Throwable failure = cause;
        for (int i = 0; i < wrappers; i++) {
            failure = new RuntimeException("Could not fetch object (wrapper " + i + ")", failure);
        }
        return failure;
    }

    @Test
    void anAzure404IsANotFoundAndAnAzure500IsNot() {
        assertThat(StorageNotFound.isNotFound(wrapped(azure(404), 1))).isTrue();
        assertThat(StorageNotFound.isNotFound(wrapped(azure(500), 1))).isFalse();
    }

    @Test
    void theNotFoundIsReadTwelveDeepAndNoDeeper() {
        // The failure itself is the first of twelve looked at, so eleven wrappers put the cause twelfth.
        assertThat(StorageNotFound.isNotFound(wrapped(new FileNotFoundException("/incoming/x.csv"), 11))).isTrue();
        assertThat(StorageNotFound.isNotFound(wrapped(new FileNotFoundException("/incoming/x.csv"), 12))).isFalse();
    }

    @Test
    void aSelfReferencingCauseEndsTheWalk() {
        RuntimeException loop = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(StorageNotFound.isNotFound(loop)).isFalse();
    }

    private static BlobStorageException azure(int status) {
        HttpResponse response = mock(HttpResponse.class);
        when(response.getStatusCode()).thenReturn(status);
        return new BlobStorageException("blob " + status, response, null);
    }
}
