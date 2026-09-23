package process.util;

import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.io.FileNotFoundException;

/**
 * Whether a storage failure means "that object is not there" rather than "the store is broken".
 *
 * Since MIG-70 the adapters live in storage-service, which does this decoding itself and answers 404;
 * StorageServiceClient carries that as a FileNotFoundException. The history below is why it matters.
 *
 * <b>Why this has to decode rather than simply catch a type.</b> Every object-storage adapter
 * wraps whatever its SDK threw in a plain {@code RuntimeException} carrying a sentence -- MinIO's
 * is {@code "Could not fetch MinIO object content <bucket>/<key>"} -- so by the time a failure
 * reaches the API layer, a missing object and an unreachable server are indistinguishable. Both
 * were answered with <b>500</b>. A caller asking for something that is not there was told the
 * server had broken, and a browser asking for a deleted avatar produced a stack trace in the log
 * on every page load.
 *
 * The cause is preserved by all of them, so the original is still in the chain and can be read.
 * This walks it and recognises each provider's own way of saying not-found.
 *
 * <b>A typed exception thrown by the adapters would be the better shape</b>, and is what to do if
 * those five classes are ever revisited. This is deliberately the smaller change: it fixes every
 * adapter at once without altering five implementations of which only one can be exercised on this
 * deployment.
 *
 * @author Nabeel Ahmed
 */
public final class StorageNotFound {

    private StorageNotFound() {}

    /** Guards against a self-referential or absurdly deep cause chain. */
    private static final int MAX_DEPTH = 12;

    public static boolean isNotFound(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (saysNotFound(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }

    private static boolean saysNotFound(Throwable ex) {
        // process's own S3 client (mail attachment staging).
        if (ex instanceof AwsServiceException) {
            return ((AwsServiceException) ex).statusCode() == 404;
        }
        // storage-service's 404, as StorageServiceClient carries it.
        return ex instanceof FileNotFoundException;
    }
}
