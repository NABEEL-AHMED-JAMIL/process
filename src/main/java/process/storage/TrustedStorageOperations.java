package process.storage;

import process.model.dto.ObjectContentDto;

import java.io.InputStream;
import java.util.List;

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

    /**
     * The objects under a prefix (MIG-231's Read S3): each key and size, recursively, folders skipped, at most
     * {@code limit} (storage-service caps it at 1000), and whether there were more.
     */
    ObjectListing listForWorkflow(TrustedAccess access, String bucket, String prefix, int limit);

    /** What a listing answered. */
    final class ObjectListing {
        public final List<ListedObject> objects;
        public final boolean truncated;

        public ObjectListing(List<ListedObject> objects, boolean truncated) {
            this.objects = objects;
            this.truncated = truncated;
        }
    }

    final class ListedObject {
        public final String key;
        public final long size;

        public ListedObject(String key, long size) {
            this.key = key;
            this.size = size;
        }
    }
}
