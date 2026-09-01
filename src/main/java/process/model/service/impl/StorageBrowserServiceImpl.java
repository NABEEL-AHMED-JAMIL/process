package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.LookupDataDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.KafkaSecretService;
import process.model.service.ObjectStorageService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.AudioTranscodeUtil;
import process.util.ContentTypeUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class StorageBrowserServiceImpl implements StorageBrowserService {

    private static final Logger logger = LoggerFactory.getLogger(StorageBrowserServiceImpl.class);
    private static final String BUCKET_LIST = "BUCKET_LIST";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;
    /** Inflated-size ceiling for previewing a .gz; beyond this, download instead. */
    private static final int MAX_GZIP_PREVIEW_BYTES = 8 * 1024 * 1024;

    private final LookupDataCacheService lookupDataCacheService;
    private final StorageConnectionRepository storageConnectionRepository;
    private final StorageClientFactory storageClientFactory;
    /** The same property AppUserServiceImpl writes every picture to; see isOwnProfileObject. */
    private final String avatarBucket;
    private final Map<String, ObjectStorageService> objectStorageServicesByProvider;

    public StorageBrowserServiceImpl(
        LookupDataCacheService lookupDataCacheService,
        StorageConnectionRepository storageConnectionRepository,
        StorageClientFactory storageClientFactory,
        @Qualifier("minioObjectStorageService") ObjectStorageService minioObjectStorageService,
        @Qualifier("s3ObjectStorageService") ObjectStorageService s3ObjectStorageService,
        @Qualifier("azureBlobObjectStorageService") ObjectStorageService azureBlobObjectStorageService,
        @Value("${app.avatar.bucket:etl-avatar}") String avatarBucket) {
        this.lookupDataCacheService = lookupDataCacheService;
        this.storageConnectionRepository = storageConnectionRepository;
        this.storageClientFactory = storageClientFactory;
        this.avatarBucket = avatarBucket;
        Map<String, ObjectStorageService> byProvider = new HashMap<>();
        byProvider.put("MINIO", minioObjectStorageService);
        byProvider.put("S3", s3ObjectStorageService);
        byProvider.put("AZURE", azureBlobObjectStorageService);
        this.objectStorageServicesByProvider = byProvider;
    }

    @Override
    public List<BucketSummaryDto> listBuckets() {
        return this.collectBuckets(false);
    }

    /**
     * The buckets configured on this platform, narrowed to the caller unless trusted.
     *
     * trusted is for the workflow paths below, which have no caller to narrow to: a scheduler or
     * startup thread carries no TenantContext, so every tenant test it could apply would refuse.
     */
    private List<BucketSummaryDto> collectBuckets(boolean trusted) {
        // A platform admin sees every bucket, and so does a trusted workflow.
        boolean unrestricted = trusted || TenantContext.isPlatformAdmin();
        Long callerTenantId = TenantContext.getTenantId();
        List<BucketSummaryDto> buckets = new ArrayList<>();

        // Storage connections are the current mechanism -- S3/Azure/FTP/FTPS/MinIO, each with
        // its own stored credentials. A tenant sees its own and nothing else.
        //
        // A connection with no tenant used to be treated as platform-wide and offered to every
        // tenant, which is how etl-bucket and etl-avatar showed up in every workspace's object
        // browser. Nothing is shared between tenants now, so those belong to the platform admin
        // alone. Note the BUCKET_LIST branch below never allowed a null tenant to match -- the
        // two halves of this method disagreed with each other.
        this.storageConnectionRepository.findByStatusNotOrderByStorageConnectionIdDesc(Status.Delete).stream()
            .filter(connection -> connection.getStatus() == Status.Active)
            .filter(connection -> unrestricted
                || belongsToCaller(connection.getTenantId(), callerTenantId))
            .forEach(connection -> buckets.add(new BucketSummaryDto(
                connection.getConnectionName(),
                connection.getAlias(),
                connection.getProvider() == null ? null : connection.getProvider().name())));

        // BUCKET_LIST lookups are the older mechanism, kept working so buckets configured that
        // way (and the jobs already pointing at them) keep resolving. A storage connection with
        // the same alias wins, which is what makes migrating one bucket at a time safe.
        Set<String> allAliases = this.storageConnectionRepository
            .findByStatusNotOrderByStorageConnectionIdDesc(Status.Delete).stream()
            .map(StorageConnection::getAlias).collect(Collectors.toSet());
        LookupDataDto bucketListParent = this.lookupDataCacheService.getParentLookupById(BUCKET_LIST);
        if (bucketListParent != null && bucketListParent.getChildren() != null) {
            bucketListParent.getChildren().stream()
                .filter(child -> unrestricted || belongsToCaller(child.getTenantId(), callerTenantId))
                // De-duplicated against every alias, not just the ones this caller may see: the
                // visible list holds no platform connection for a tenant, so matching on it let a
                // tenant lookup child called "etl-bucket" appear as though it were theirs.
                .filter(child -> !allAliases.contains(child.getLookupValue()))
                // And the same lookup child when no connection carries that alias at all, which is
                // how the platform buckets are actually shipped -- see isPlatformBucket.
                .filter(child -> unrestricted || !this.isPlatformBucketName(child.getLookupValue()))
                .forEach(child -> buckets.add(new BucketSummaryDto(
                    child.getLookupType(), child.getLookupValue(), child.getDescription())));
        }
        return buckets;
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        this.requireSafeKey(prefix);
        int pageSize = maxKeys <= 0 ? DEFAULT_PAGE_SIZE : Math.min(maxKeys, MAX_PAGE_SIZE);
        return this.resolveServiceForCaller(bucket, null).listObjects(bucket, prefix, continuationToken, pageSize);
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        this.requireSafeKey(key);
        return this.resolveServiceForCaller(bucket, key).getObjectMetadata(bucket, key);
    }

    @Override
    // Same guard as fileChatExtract: callers explicitly handle a null metadata result, and the
    // cache rejects nulls, so without this an unreadable object throws instead of being reported.
    //
    // The caller is part of the key because the authorisation lives in the body, which a cache hit
    // skips: on bucket+key alone, the one lookup a platform admin makes in a platform bucket would
    // be served verbatim to the next person who names the same pair. Keying on who asked means an
    // entry can only ever be handed back to the caller the guard already admitted.
    @Cacheable(value = "fileChatMetadata",
        key = "T(process.security.TenantContext).getTenantId() + ':'"
            + " + T(process.security.TenantContext).getAppUserId() + ':' + #bucket + ':' + #key",
        unless = "#result == null")
    public ObjectMetadataDto getObjectMetadataCached(String bucket, String key) {
        return this.getObjectMetadata(bucket, key);
    }

    @Override
    public ObjectContentDto previewObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        this.requireSafeKey(key);
        if (!ContentTypeUtil.isPreviewable(key)) {
            throw new IllegalArgumentException("Preview is not supported for this file type; use download instead.");
        }
        if (ContentTypeUtil.isPreviewableGzip(key)) {
            return this.previewGzip(bucket, key);
        }
        return this.resolveServiceForCaller(bucket, key).getObjectContent(bucket, key, rangeStart, rangeEnd);
    }

    /**
     * Serves a gzipped text file as its decompressed contents, typed by what's inside
     * ("audit.json.gz" is delivered as JSON). Handing the browser the raw gzip bytes would just
     * render as binary noise, and a byte range through a compressed stream is meaningless, so
     * this reads and inflates whole rather than honouring range requests.
     */
    private ObjectContentDto previewGzip(String bucket, String key) {
        ObjectContentDto compressed = this.resolveServiceForCaller(bucket, key).getObjectContent(bucket, key, null, null);
        String innerName = key.substring(0, key.length() - ".gz".length());
        try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(compressed.getContent());
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = gzip.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > MAX_GZIP_PREVIEW_BYTES) {
                    // A small archive can inflate to something enormous, so stop at a size the
                    // browser can actually render instead of trying to hold all of it.
                    throw new IllegalArgumentException(
                        "This file is too large to preview once decompressed -- download it instead.");
                }
            }
            byte[] decompressed = out.toByteArray();
            return new ObjectContentDto(
                new java.io.ByteArrayInputStream(decompressed),
                ContentTypeUtil.contentTypeFor(innerName),
                decompressed.length,
                this.fileNameOf(innerName));
        } catch (java.util.zip.ZipException e) {
            throw new IllegalArgumentException(
                "This file has a .gz name but isn't valid gzip data -- download it instead.");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decompress " + key, e);
        }
    }

    private String fileNameOf(String key) {
        int slash = key.lastIndexOf('/');
        return slash >= 0 ? key.substring(slash + 1) : key;
    }

    @Override
    public ObjectContentDto downloadObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        this.requireSafeKey(key);
        return this.resolveServiceForCaller(bucket, key).getObjectContent(bucket, key, rangeStart, rangeEnd);
    }

    @Override
    public void uploadObject(String bucket, String prefix, MultipartFile file) {
        this.uploadMultipart(bucket, prefix, file, false);
    }

    /**
     * Trusted: an upload an application workflow makes on the caller's behalf.
     *
     * The guarded methods above refuse a platform bucket to anyone but a platform admin, which is
     * right for a request that names its own bucket and key -- and wrong for a workflow, whose
     * whole job is to put a file somewhere the user could never name for themselves: a PDF
     * highlighter task's own folder, a Kafka profile's truststore. The caller has already decided
     * the row belongs to whoever is asking and builds the key from that row, so there is nothing
     * left here to check that it has not checked better. That also means a caller must never pass
     * a bucket or key straight through from a request, and none of these may be exposed on
     * StorageBrowserRestApi.
     */
    @Override
    public void uploadForWorkflow(String bucket, String prefix, MultipartFile file) {
        this.uploadMultipart(bucket, prefix, file, true);
    }

    /**
     * The shared body of the two multipart uploads; trusted says which resolver authorises it.
     */
    private void uploadMultipart(String bucket, String prefix, MultipartFile file, boolean trusted) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        String originalFileName = file.getOriginalFilename();
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            throw new IllegalArgumentException("Uploaded file has no name.");
        }
        String safeFileName = Paths.get(originalFileName).getFileName().toString();
        String key = this.normalizedPrefix(prefix) + safeFileName;
        // The prefix was checked on its own, but a name Paths.get leaves a separator in -- a
        // literal "a\b.txt" on a Linux JVM -- composes a key every read path would then refuse.
        this.requireSafeKey(key);
        ObjectStorageService service = trusted
            ? this.resolveService(bucket, true)
            : this.resolveServiceForCaller(bucket, key);
        String extension = ContentTypeUtil.extensionOf(safeFileName);
        if (!AudioTranscodeUtil.isAudioExtension(extension)) {
            try {
                service.uploadObject(bucket, key, file.getInputStream(), file.getSize(), file.getContentType());
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read uploaded file " + safeFileName, e);
            }
            return;
        }

        Path tempInput = null;
        Path tempOutput = null;
        try {
            tempInput = Files.createTempFile("upload-", "." + extension);
            file.transferTo(tempInput);
            tempOutput = AudioTranscodeUtil.transcodeToAacIfNeeded(tempInput);
            Path uploadSource = tempOutput != null ? tempOutput : tempInput;
            try (InputStream in = Files.newInputStream(uploadSource)) {
                service.uploadObject(bucket, key, in, Files.size(uploadSource), ContentTypeUtil.contentTypeFor(key));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file " + safeFileName, e);
        } finally {
            AudioTranscodeUtil.deleteQuietly(tempInput);
            AudioTranscodeUtil.deleteQuietly(tempOutput);
        }
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream inputStream, long size, String contentType) {
        this.requireSafeKey(key);
        this.resolveServiceForCaller(bucket, key).uploadObject(bucket, key, inputStream, size, contentType);
    }

    /** Trusted, on the same terms as uploadForWorkflow above. */
    @Override
    public void uploadForWorkflow(String bucket, String key, InputStream inputStream, long size, String contentType) {
        this.requireSafeKey(key);
        this.resolveService(bucket, true).uploadObject(bucket, key, inputStream, size, contentType);
    }

    /** Trusted, on the same terms as uploadForWorkflow above: the caller owns the row this key came from. */
    @Override
    public ObjectContentDto readForWorkflow(String bucket, String key) {
        this.requireSafeKey(key);
        return this.resolveService(bucket, true).getObjectContent(bucket, key, null, null);
    }

    @Override
    public void createFolder(String bucket, String prefix, String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            throw new IllegalArgumentException("Folder name is required.");
        }

        String safeFolderName = folderName.trim().replace("/", "").replace("\\", "");
        if (safeFolderName.isEmpty()) {
            throw new IllegalArgumentException("Folder name is required.");
        }
        String folderKey = this.normalizedPrefix(prefix) + safeFolderName + "/";
        // Stripping the separators out of the name still leaves ".." as a name in its own right.
        this.requireSafeKey(folderKey);
        this.resolveServiceForCaller(bucket, folderKey).createFolder(bucket, folderKey);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        this.requireSafeKey(key);
        this.resolveServiceForCaller(bucket, key).deleteObject(bucket, key);
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("No keys given to delete.");
        }
        for (String key : keys) {
            this.requireSafeKey(key);
        }
        this.resolveServiceForCaller(bucket, null).deleteObjects(bucket, keys);
    }

    @Override
    public void deleteFolder(String bucket, String folderKey) {
        this.requireFolderKey(folderKey);
        this.requireSafeKey(folderKey);
        this.resolveServiceForCaller(bucket, folderKey).deleteFolder(bucket, folderKey);
    }

    @Override
    public void renameFolder(String bucket, String folderKey, String newFolderName) {
        this.requireFolderKey(folderKey);
        this.requireSafeKey(folderKey);
        if (newFolderName == null || newFolderName.trim().isEmpty()) {
            throw new IllegalArgumentException("New folder name is required.");
        }
        String safeNewName = newFolderName.trim().replace("/", "").replace("\\", "");
        if (safeNewName.isEmpty()) {
            throw new IllegalArgumentException("New folder name is required.");
        }

        String trimmedKey = folderKey.substring(0, folderKey.length() - 1);
        int lastSlash = trimmedKey.lastIndexOf('/');
        String parentPrefix = lastSlash >= 0 ? trimmedKey.substring(0, lastSlash + 1) : "";
        String newPrefix = parentPrefix + safeNewName + "/";
        // Stripping the separators out of the name still leaves ".." as a name in its own right.
        this.requireSafeKey(newPrefix);
        if (newPrefix.equals(folderKey)) {
            return;
        }
        this.resolveServiceForCaller(bucket, folderKey).renameFolder(bucket, folderKey, newPrefix);
    }

    private void requireFolderKey(String folderKey) {
        if (folderKey == null || folderKey.trim().isEmpty() || !folderKey.endsWith("/")) {
            throw new IllegalArgumentException("A folder key must end with '/'.");
        }
    }

    private String normalizedPrefix(String prefix) {
        this.requireSafeKey(prefix);
        return prefix == null ? "" : prefix;
    }

    /**
     * Refuses a key that could name a different object than it reads as.
     *
     * The FTP backends collapse "a/../b" down to "b" of their own accord, and they do it after
     * this class has decided whose key it is -- so "7/profile/../../9/profile/avatar.png" would
     * satisfy the own-profile test below and then land in somebody else's folder. A backslash is
     * the same trick with the other separator. Neither has any honest use in a key here, so both
     * are refused rather than rewritten: rewriting would act on a key the caller never asked for.
     */
    private void requireSafeKey(String key) {
        if (!isSafeKey(key)) {
            throw new IllegalArgumentException("Invalid key: " + key + ".");
        }
    }

    private static boolean isSafeKey(String key) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        if (key.indexOf('\\') >= 0 || key.startsWith("/")) {
            return false;
        }
        for (String segment : key.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolution for a bucket and key the caller named in a request -- read, write or list alike.
     *
     * resolveService below deliberately lets a platform bucket through for everybody, because an
     * application workflow reaching a file it wrote has no tenant of its own to be matched on.
     * A request that arrived from a browser gets no such licence, on any verb: every user's
     * picture lives in etl-avatar, and etl-bucket holds Kafka key material, PDF uploads and
     * other tenants' documents, so an unguarded path would let one tenant read, enumerate,
     * rename or delete another's. The one object a tenant user genuinely owns in a platform
     * bucket is their own avatar, and that is the only exception made.
     */
    private ObjectStorageService resolveServiceForCaller(String bucket, String key) {
        if (this.isPlatformBucket(bucket)
            && !TenantContext.isPlatformAdmin() && !this.isOwnProfileObject(bucket, key)) {
            throw new IllegalArgumentException("Unknown bucket: " + bucket + ".");
        }
        return this.resolveService(bucket, false);
    }

    /**
     * Whether a bucket is one the platform manages rather than a tenant.
     *
     * The two default buckets are named here, not merely inferred from a tenant-less storage
     * connection, because no migration creates a connection row for either of them: etl-bucket
     * ships as a BUCKET_LIST lookup entry and etl-avatar as a property. BUCKET_LIST is a family a
     * tenant admin may add to, so with the guard resting on a row that does not exist, a lookup
     * entry of their own called "etl-bucket" was enough to be handed the platform's own client --
     * and with it every tenant's Kafka key material and every user's picture. Naming the buckets
     * keeps the answer the same however they come to be configured.
     *
     * The connection lookup still stands for every other platform bucket an operator adds, and
     * reads any status: a retired or soft-deleted row still names a real bucket, and matching only
     * Active ones let the guard fall through to the legacy path. The rule follows the bucket, not
     * the row's lifecycle.
     */
    private boolean isPlatformBucket(String bucket) {
        if (bucket == null) {
            return false;
        }
        if (this.isPlatformBucketName(bucket)) {
            return true;
        }
        Optional<StorageConnection> connection = this.storageConnectionRepository.findByAlias(bucket);
        return connection.isPresent() && connection.get().getTenantId() == null;
    }

    private boolean isPlatformBucketName(String bucket) {
        return this.avatarBucket.equals(bucket) || KafkaSecretService.SECRET_BUCKET.equals(bucket);
    }

    /**
     * Whether a configured row is the caller's own.
     *
     * Both tenants have to be present, not merely equal: a platform row carries no tenant, and on
     * Objects.equals alone it became the property of any caller who also had none -- a request
     * whose tenant could not be resolved, or a background thread that never had one.
     */
    private static boolean belongsToCaller(Long rowTenantId, Long callerTenantId) {
        return rowTenantId != null && rowTenantId.equals(callerTenantId);
    }

    /**
     * Whether a key is the caller's own profile picture.
     *
     * The one object a tenant user legitimately reaches in a platform bucket: avatars all live in
     * the avatar bucket, under <appUserId>/profile/. Scoped to their own id, so this permits
     * replacing their picture and nothing else -- not a neighbour's, and not anywhere outside the
     * folder. The trailing separator is what stops user 1248 from matching "12480/profile/", and
     * the traversal check is what stops the key from being pointed elsewhere after the fact.
     *
     * The bucket is checked too, because the folder name is the whole of the rest of the test: on
     * key alone the exception reached etl-bucket as well, where "7/profile/" names nothing anybody
     * owns and the neighbouring folders hold Kafka key material and other tenants' documents.
     *
     * A null key means the caller is listing rather than naming an object, which is never
     * somebody's own profile object and so never allowed by this.
     */
    private boolean isOwnProfileObject(String bucket, String key) {
        Long callerId = TenantContext.getAppUserId();
        if (key == null || callerId == null || !isSafeKey(key) || !this.avatarBucket.equals(bucket)) {
            return false;
        }
        // An object, never a folder. This exception exists so somebody can put their own picture
        // in a platform bucket and read it back, and a trailing slash means the caller is naming a
        // prefix instead -- which is how deleteFolder and renameFolder arrive. "1248/profile/"
        // starts with "1248/profile/", so without this line those two were permitted against the
        // caller's own folder, and the comment claiming a folder key could never qualify was
        // simply wrong.
        if (key.endsWith("/")) {
            return false;
        }
        return key.startsWith(callerId + "/profile/");
    }

    /**
     * Resolves a bucket to the client that serves it.
     *
     * trusted is the workflow paths' resolution: it skips the tenant test below and reads the
     * legacy bucket list unnarrowed, because the threads that use it -- the topic provisioner at
     * startup, a scheduled dispatch -- carry no TenantContext, so every test would find nobody to
     * match and refuse a bucket the workflow wrote itself. Never reachable from a request that
     * named its own bucket; see uploadForWorkflow.
     */
    private ObjectStorageService resolveService(String bucket, boolean trusted) {
        Optional<StorageConnection> connection = this.storageConnectionRepository.findByAliasAndStatus(bucket, Status.Active);
        if (connection.isPresent()) {
            StorageConnection storageConnection = connection.get();
            if (!trusted && !TenantContext.isPlatformAdmin()
                && storageConnection.getTenantId() != null
                && !Objects.equals(storageConnection.getTenantId(), TenantContext.getTenantId())) {
                throw new IllegalArgumentException("Unknown bucket: " + bucket + ".");
            }
            ObjectStorageService service = this.storageClientFactory.serviceFor(storageConnection);
            // FTP has no bucket concept, so there is nothing to rewrite; for the object stores
            // the alias may differ from the real bucket/container name.
            if (storageConnection.getProvider() != null && storageConnection.getProvider().isFtpFamily()) {
                return service;
            }
            String realBucket = storageConnection.getBucketName() != null
                && !storageConnection.getBucketName().trim().isEmpty()
                    ? storageConnection.getBucketName().trim()
                    : storageConnection.getAlias();
            return realBucket.equals(bucket) ? service : new BucketRewritingStorageService(service, realBucket);
        }

        BucketSummaryDto bucketSummary = this.collectBuckets(trusted).stream()
            .filter(b -> bucket.equals(b.getBucket()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown bucket: " + bucket + ". Add a storage connection for it first."));
        String provider = bucketSummary.getProvider();
        ObjectStorageService service = provider != null
            ? this.objectStorageServicesByProvider.get(provider.trim().toUpperCase())
            : null;
        if (service == null) {
            throw new IllegalStateException(
                "Unsupported/missing storage provider '" + provider + "' for bucket " + bucket
                    + ". Configure it as a storage connection, or set the BUCKET_LIST lookup entry's"
                    + " description to MINIO, S3, or AZURE.");
        }
        return service;
    }

}
