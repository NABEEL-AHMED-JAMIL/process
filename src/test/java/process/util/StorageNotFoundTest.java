package process.util;

import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.http.SdkHttpResponse;

import java.io.FileNotFoundException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That "the object is not there" is told apart from "the store is broken".
 *
 * Every object-storage adapter wraps its SDK's exception in a plain RuntimeException carrying a
 * sentence, so by the time a failure reaches the API both look identical and both were answered
 * 500. A deleted avatar logged a stack trace on every page load, and a caller asking for something
 * absent was told the server had failed.
 *
 * The cause survives the wrapping, which is what makes this decodable at all. These tests pin
 * both directions: a genuine not-found is recognised THROUGH the wrapper, and a real fault is not
 * mistaken for one -- the second matters more, because a store that is down being reported as
 * 404 would read as "your data is gone".
 *
 * @author Nabeel Ahmed
 */
class StorageNotFoundTest {

    /** The shape every adapter produces: the SDK's exception behind a sentence of its own. */
    private static RuntimeException asAdapterWraps(Throwable cause) {
        return new RuntimeException("Could not fetch MinIO object content etl-bucket/a/b.txt", cause);
    }

    private static ErrorResponseException minioError(String code) throws Exception {
        ErrorResponse response = new ErrorResponse(code, "message", "etl-bucket", "a/b.txt",
            "req", "host", "id");
        return new ErrorResponseException(response, null, null);
    }

    private static AwsServiceException awsWithStatus(int status) {
        return AwsServiceException.builder()
            .awsErrorDetails(AwsErrorDetails.builder()
                .sdkHttpResponse(SdkHttpResponse.builder().statusCode(status).build())
                .build())
            .statusCode(status)
            .build();
    }

    @Test
    void aMissingMinioObjectIsRecognisedThroughTheAdaptersWrapper() throws Exception {
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(minioError("NoSuchKey")))).isTrue();
    }

    @Test
    void aMissingMinioBucketCountsToo() throws Exception {
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(minioError("NoSuchBucket")))).isTrue();
    }

    @Test
    void aMinioPermissionFailureIsNotANotFound() throws Exception {
        // Reporting "access denied" as 404 tells the caller their data is gone when it is there
        // and they simply may not read it -- a worse answer than either true one.
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(minioError("AccessDenied")))).isFalse();
    }

    @Test
    void anS3FourOhFourIsANotFound() {
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(awsWithStatus(404)))).isTrue();
    }

    @Test
    void anS3ServerErrorIsNot() {
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(awsWithStatus(500)))).isFalse();
        assertThat(StorageNotFound.isNotFound(asAdapterWraps(awsWithStatus(403)))).isFalse();
    }

    @Test
    void anFtpMissingPathIsANotFound() {
        assertThat(StorageNotFound.isNotFound(
            asAdapterWraps(new FileNotFoundException("/drop/in/a.csv")))).isTrue();
    }

    @Test
    void aStoreThatCannotBeReachedIsNotANotFound() {
        // The case this must never get wrong. An unreachable store answered 404 would report every
        // object in the platform as missing for as long as the outage lasted.
        assertThat(StorageNotFound.isNotFound(
            asAdapterWraps(new IOException("Connection refused")))).isFalse();
    }

    @Test
    void anUnwrappedNotFoundIsStillRecognised() throws Exception {
        // Not every caller wraps; the walk has to handle depth zero.
        assertThat(StorageNotFound.isNotFound(minioError("NoSuchKey"))).isTrue();
    }

    @Test
    void itIsFoundSeveralCausesDeep() throws Exception {
        Throwable deep = new RuntimeException("outer",
            new IllegalStateException("middle", asAdapterWraps(minioError("NoSuchKey"))));

        assertThat(StorageNotFound.isNotFound(deep)).isTrue();
    }

    @Test
    void aCyclicCauseChainTerminates() {
        // Java refuses direct self-causation, but a cycle through TWO throwables is perfectly
        // constructible and is what would otherwise spin here for ever, taking the request thread
        // with it. The depth ceiling is what stops it.
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        assertThat(StorageNotFound.isNotFound(first)).isFalse();
    }

    @Test
    void nothingIsNotANotFound() {
        assertThat(StorageNotFound.isNotFound(null)).isFalse();
    }
}
