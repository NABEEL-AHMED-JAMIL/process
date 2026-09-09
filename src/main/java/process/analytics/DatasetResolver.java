package process.analytics;

import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantOwnership;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Turns "connection 12, bucket etl-bucket, path orders/2026/*.csv" into something readable.
 *
 * The only constructor of DatasetRef is package-private, so this class is the sole way to obtain
 * one: the tenant check, the path check and the format check cannot be forgotten at a call site
 * because there is no call site that can skip them.
 *
 * The refusals here are deliberately uniform. A caller asking for a connection belonging to
 * another workspace gets the same answer as one asking for a connection that does not exist,
 * because telling the two apart is how an id becomes an enumeration oracle.
 *
 * @author Nabeel Ahmed
 */
@Component
public class DatasetResolver {

    /**
     * What a path segment may contain.
     *
     * Deliberately an allow-list. A deny-list of "../" and friends invites the reader to think of
     * one more encoding; this permits the characters object keys actually use, plus the two glob
     * characters a multi-file dataset needs, and refuses everything else. Notably absent: the
     * quote that would end a SQL literal, and the backslash that would escape one.
     */
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._*?/=+ -]+");

    /** Same idea, without the glob characters: a bucket is always one exact name. */
    private static final Pattern SAFE_BUCKET = Pattern.compile("[A-Za-z0-9._-]+");

    private final StorageConnectionRepository storageConnectionRepository;

    public DatasetResolver(StorageConnectionRepository storageConnectionRepository) {
        this.storageConnectionRepository = storageConnectionRepository;
    }

    /**
     * Resolves a request into a location this caller is allowed to read, or explains why not.
     *
     * The caller names a CONNECTION, by the same alias the object browser uses, and a path inside
     * it. It never names a bucket: the bucket is whatever the connection record is bound to, so
     * "read a different bucket with these credentials" is not a request this API can express.
     *
     * @throws AnalyticsException with a message intended for a user, never a stack trace
     */
    public DatasetRef resolve(String connectionAlias, String path) throws AnalyticsException {
        if (isBlank(connectionAlias)) {
            throw new AnalyticsException("Pick a storage connection first.");
        }
        if (isBlank(path)) {
            throw new AnalyticsException("Pick a file or a folder pattern first.");
        }

        String cleanPath = path.trim().replaceAll("^/+", "");
        if (!SAFE_PATH.matcher(cleanPath).matches()) {
            throw new AnalyticsException("That path contains characters this reader does not accept.");
        }
        // Checked after the allow-list rather than instead of it: ".." is made of permitted
        // characters, and a path that climbs is the one shape the allow-list cannot exclude.
        if (cleanPath.contains("..")) {
            throw new AnalyticsException("A dataset path cannot contain \"..\".");
        }

        // isOwnedByCaller, NOT isVisibleToCaller. The difference is the whole tenant boundary
        // here: isVisibleToCaller publishes a row with a null tenant to EVERY tenant, which is
        // right for the shared catalogues it was written for -- lookups, task types -- and wrong
        // for this table. A storage_connection carrying no tenant is platform-owned, and
        // StorageBrowserServiceImpl refuses it to everyone but a platform admin
        // (collectBuckets :105-107 via belongsToCaller, isPlatformBucket :464). Reading it here
        // would have made this API more permissive than the object browser it borrows its
        // connections from, which is the one thing it must never be.
        //
        // Active, not merely not-Deleted, for the same reason: the picker only offers Active
        // connections (collectBuckets :104), so anything looser lets a deliberately deactivated
        // connection keep serving data through an API where nobody can see it is still live.
        Optional<StorageConnection> found = this.storageConnectionRepository
            .findByAlias(connectionAlias.trim())
            .filter(c -> Status.Active.equals(c.getStatus()))
            .filter(c -> TenantOwnership.isOwnedByCaller(c.getTenantId()));
        if (!found.isPresent()) {
            // Same wording for absent and for forbidden. A different message for each would let a
            // caller walk the alias space and learn which connections other workspaces own.
            throw new AnalyticsException("Storage connection not found.");
        }
        StorageConnection connection = found.get();
        if (connection.getProvider() == null || !connection.getProvider().isObjectStore()) {
            throw new AnalyticsException("Analytics Studio reads object storage. "
                + "This connection is " + connection.getProvider() + ".");
        }
        // AZURE passes isObjectStore(), and that is exactly the problem. DuckDbSessionFactory has
        // an Azure branch (:130-134, azureSecret :171-179) that was written from the documentation
        // and has never been run against a real container: a different DuckDB extension from
        // httpfs, a different secret shape and a different URL scheme, so the S3 and MinIO
        // verification is evidence for none of it. Without this gate an Azure connection reaches
        // the engine, fails in a way nobody has ever seen, and lands in explain()'s unmapped
        // branch as "The dataset could not be read." -- the one useless answer this module spent
        // its whole error path avoiding.
        //
        // So the message says UNVERIFIED, not unsupported. The code is there and may well work;
        // what is missing is that anybody checked, and a user is better served knowing that than
        // being told a tidier untruth in either direction. Delete this block when somebody has
        // pointed it at a real container -- and not before.
        //
        // The message no longer names what HAS been verified, and that correction matters more
        // than it looks. It used to end "S3 and MinIO have been." -- but every connection this was
        // ever run against carries an explicit endpoint (MinIO on :9000, LocalStack on :4566), so
        // what was verified is the S3 PROTOCOL against a local endpoint. The branch in
        // s3Secret that handles a blank endpoint -- real AWS, virtual-host addressing, a region
        // that actually resolves -- has never executed. Claiming AWS S3 was verified, inside the
        // very sentence that exists to avoid overclaiming about Azure, was the same mistake one
        // provider over.
        if (StorageProvider.AZURE.equals(connection.getProvider())) {
            throw new AnalyticsException("Analytics Studio has not been verified against Azure "
                + "Blob yet, so it will not read this connection.");
        }

        // The bucket comes from the record, never from the request. An alias whose connection
        // names no bucket falls back to the alias, which is what StorageBrowserServiceImpl does.
        String cleanBucket = isBlank(connection.getBucketName())
            ? connection.getAlias().trim()
            : connection.getBucketName().trim();
        if (!SAFE_BUCKET.matcher(cleanBucket).matches()) {
            throw new AnalyticsException("This connection's bucket name cannot be read by the "
                + "analytics engine.");
        }

        DatasetRef.Format format = DatasetRef.Format.of(cleanPath);
        if (format == null) {
            throw new AnalyticsException("Analytics Studio does not read this file type yet. "
                + "It reads CSV, TSV, JSON and Parquet.");
        }
        return new DatasetRef(connection, cleanBucket, cleanPath, format);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
