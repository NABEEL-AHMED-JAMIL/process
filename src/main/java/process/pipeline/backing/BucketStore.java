package process.pipeline.backing;

import java.util.List;
import java.util.Optional;

/**
 * A workspace's buckets, as a pipeline's steps reach them (MIG-231: Read S3, Read CSV/JSON/Parquet, Upload to bucket):
 * through storage-service's trusted contract as CORE_PIPELINES, in the run's workspace, whose bucket alias resolves to
 * that workspace's own storage connection only.
 */
public interface BucketStore {

    Optional<String> unavailable();

    Listing list(long tenantId, String bucket, String prefix, int limit) throws Exception;

    /** The object's bytes; an exception when it is larger than {@code maxBytes}. */
    byte[] read(long tenantId, String bucket, String key, long maxBytes) throws Exception;

    void upload(long tenantId, String bucket, String key, byte[] content, String contentType) throws Exception;

    final class Listing {
        public final List<Listed> objects;
        public final boolean truncated;

        public Listing(List<Listed> objects, boolean truncated) {
            this.objects = objects;
            this.truncated = truncated;
        }
    }

    final class Listed {
        public final String key;
        public final long size;

        public Listed(String key, long size) {
            this.key = key;
            this.size = size;
        }
    }
}
