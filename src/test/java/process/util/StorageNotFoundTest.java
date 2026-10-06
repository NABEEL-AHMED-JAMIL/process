package process.util;

import org.junit.jupiter.api.Test;

import java.io.FileNotFoundException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What process can still see since MIG-70: storage-service's 404, carried by StorageServiceClient as a
 * FileNotFoundException cause. The providers' own ways of saying not-found are decoded in
 * storage-service, where this class's tests went with the adapters.
 */
class StorageNotFoundTest {

    private static RuntimeException storageAnswered404() {
        return new IllegalStateException("No object at etl-avatar/61/profile/a.png.",
            new FileNotFoundException("No object at etl-avatar/61/profile/a.png."));
    }

    @Test
    void storageServicesFourOhFourIsANotFound() {
        assertThat(StorageNotFound.isNotFound(storageAnswered404())).isTrue();
    }

    @Test
    void anOutageIsNot() {
        assertThat(StorageNotFound.isNotFound(new IllegalStateException("Storage could not be reached: refused",
            new ConnectException("refused")))).isFalse();
    }

    @Test
    void itIsFoundSeveralCausesDeepAndNoDeeperThanTwelve() {
        Throwable chain = new FileNotFoundException("gone");
        for (int i = 0; i < 11; i++) {
            chain = new RuntimeException("layer " + i, chain);
        }
        assertThat(StorageNotFound.isNotFound(chain)).as("twelve deep").isTrue();
        assertThat(StorageNotFound.isNotFound(new RuntimeException("one more", chain))).as("thirteen deep").isFalse();
    }

    @Test
    void aCyclicCauseChainTerminates() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b", a);
        a.initCause(b);
        assertThat(StorageNotFound.isNotFound(a)).isFalse();
    }

    @Test
    void nothingIsNotANotFound() {
        assertThat(StorageNotFound.isNotFound(null)).isFalse();
    }
}
