package process.storage;

import process.model.dto.ObjectContentDto;

import java.io.InputStream;

/**
 * Storage's trusted operations (MIG-52): the ones that skip the check keeping a caller out of a
 * platform bucket, because the workflow has already established that the row the key comes from is
 * the caller's and builds the key itself -- a PDF highlighter task it owns, a Kafka profile's
 * truststore, a user's own picture, an invoice. Every call presents a TrustedAccess and is audited.
 *
 * Separate from StorageBrowserService on purpose: the guarded interface is what controllers and
 * ordinary services are handed, and nothing on it can grant trust. Never exposed on a controller.
 * Uploads here are deliberately unmetered (DEF-021: a billing PDF is free).
 */
public interface TrustedStorageOperations {

    void uploadForWorkflow(TrustedAccess access, String bucket, String key, InputStream inputStream, long size, String contentType);

    ObjectContentDto readForWorkflow(TrustedAccess access, String bucket, String key);
}
