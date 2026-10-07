package process.pipeline.backing;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.io.ByteArrayOutputStream;

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

    /**
     * MIG-344: the object as a stream for a step that reads it as one (a CSV read row by row), failing past
     * {@code maxBytes}. Billed as storage-service bills a trusted read: the bytes the step took. The default reads it whole.
     * The caller closes it.
     */
    default InputStream open(long tenantId, String bucket, String key, long maxBytes) throws Exception {
        return new ByteArrayInputStream(this.read(tenantId, bucket, key, maxBytes));
    }

    /** MIG-344: an upload from a stream of {@code size} bytes (a file a step spooled to disk). The default reads it whole. */
    default void upload(long tenantId, String bucket, String key, InputStream content, long size, String contentType) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        int n;
        while ((n = content.read(chunk)) != -1) {
            bytes.write(chunk, 0, n);
        }
        this.upload(tenantId, bucket, key, bytes.toByteArray(), contentType);
    }

    /**
     * MIG-334: the object as a stream, for the customer API's download of a file -- its bytes are never held whole. The
     * default reads it whole (at most 100 MB, the upload limit); the trusted store streams it. The caller closes it.
     */
    default Streamed stream(long tenantId, String bucket, String key) throws Exception {
        byte[] bytes = this.read(tenantId, bucket, key, 100L * 1024 * 1024);
        return new Streamed(new ByteArrayInputStream(bytes), bytes.length, null);
    }

    /** An object's bytes as a stream, its size and, when storage knows it, its type. */
    final class Streamed {
        public final InputStream content;
        public final long size;
        public final String contentType;

        public Streamed(InputStream content, long size, String contentType) {
            this.content = content;
            this.size = size;
            this.contentType = contentType;
        }
    }

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
