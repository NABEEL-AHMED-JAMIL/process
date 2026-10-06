package process.pipeline.backing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.model.dto.ObjectContentDto;
import process.storage.TrustedAccess;
import process.storage.TrustedCaller;
import process.storage.TrustedStorageOperations;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A pipeline's bucket steps through storage-service's trusted contract (MIG-231), as {@link TrustedCaller#CORE_PIPELINES}
 * in the run's workspace: list, read, upload -- the bucket alias resolving to that workspace's own connection only.
 * Every call names its workspace from the run; the bucket and key come from the definition an admin saved.
 *
 * storage-service must accept CORE_PIPELINES first; until it does, process.pipeline.storage.trusted-caller is false
 * and the bucket tasks are listed as unavailable.
 */
@Component
public class TrustedBucketStore implements BucketStore {

    private final TrustedStorageOperations storage;
    private final boolean accepted;

    public TrustedBucketStore(TrustedStorageOperations storage,
        @Value("${process.pipeline.storage.trusted-caller:false}") boolean accepted) {
        this.storage = storage;
        this.accepted = accepted;
    }

    @Override
    public Optional<String> unavailable() {
        return this.accepted ? Optional.<String>empty()
            : Optional.of("storage-service does not accept Core's pipeline caller (CORE_PIPELINES) yet "
                + "(process.pipeline.storage.trusted-caller is off)");
    }

    private TrustedAccess access(long tenantId, String reason) {
        this.require();
        return TrustedAccess.of(TrustedCaller.CORE_PIPELINES, reason).forTenant(tenantId);
    }

    private void require() {
        Optional<String> unavailable = this.unavailable();
        if (unavailable.isPresent()) {
            throw new IllegalStateException(unavailable.get() + ".");
        }
    }

    @Override
    public Listing list(long tenantId, String bucket, String prefix, int limit) {
        TrustedStorageOperations.ObjectListing listing = this.storage.listForWorkflow(this.access(tenantId, "pipeline read s3"),
            bucket, prefix, limit);
        List<Listed> objects = new ArrayList<>();
        for (TrustedStorageOperations.ListedObject object : listing.objects) {
            objects.add(new Listed(object.key, object.size));
        }
        return new Listing(objects, listing.truncated);
    }

    @Override
    public byte[] read(long tenantId, String bucket, String key, long maxBytes) throws Exception {
        ObjectContentDto content = this.storage.readForWorkflow(this.access(tenantId, "pipeline read file"), bucket, key);
        try (InputStream in = content.getContent()) {
            if (content.getSize() > maxBytes) {
                throw new IllegalStateException(String.format("%s/%s is %,d bytes; a step reads at most %,d.", bucket, key,
                    content.getSize(), maxBytes));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
                if (out.size() > maxBytes) {
                    throw new IllegalStateException(String.format("%s/%s is more than %,d bytes, the most a step reads.", bucket, key, maxBytes));
                }
            }
            return out.toByteArray();
        }
    }

    @Override
    public Streamed stream(long tenantId, String bucket, String key) {
        ObjectContentDto content = this.storage.readForWorkflow(this.access(tenantId, "customer api file"), bucket, key);
        return new Streamed(content.getContent(), content.getSize(), content.getContentType());
    }

    @Override
    public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
        this.storage.uploadForWorkflow(this.access(tenantId, "pipeline upload"), bucket, key, new ByteArrayInputStream(content),
            content.length, contentType);
    }
}
