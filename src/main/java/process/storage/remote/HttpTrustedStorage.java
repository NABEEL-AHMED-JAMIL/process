package process.storage.remote;

import process.model.dto.ObjectContentDto;
import process.storage.TrustedAccess;
import process.storage.TrustedStorageOperations;

import java.io.InputStream;

/**
 * The trusted workflow operations, answered by storage-service (MIG-184) for the four named callers.
 * The principal travels with every call; Storage audits it, resolves within the row's workspace and
 * announces the upload before it writes.
 */
public class HttpTrustedStorage implements TrustedStorageOperations {

    private final StorageServiceClient storage;

    public HttpTrustedStorage(StorageServiceClient storage) {
        this.storage = storage;
    }

    @Override
    public void uploadForWorkflow(TrustedAccess access, String bucket, String key, InputStream inputStream, long size,
        String contentType) {
        requireAccess(access);
        this.storage.trustedUpload(access, bucket, key, inputStream, size, contentType);
    }

    @Override
    public ObjectContentDto readForWorkflow(TrustedAccess access, String bucket, String key) {
        requireAccess(access);
        return this.storage.trustedRead(access, bucket, key);
    }

    private static void requireAccess(TrustedAccess access) {
        if (access == null) {
            throw new IllegalStateException("A trusted storage call presents its principal.");
        }
    }
}
