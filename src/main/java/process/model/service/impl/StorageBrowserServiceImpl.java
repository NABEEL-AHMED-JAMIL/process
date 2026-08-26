package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.stream.Collectors;

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
    private final Map<String, ObjectStorageService> objectStorageServicesByProvider;

    public StorageBrowserServiceImpl(
        LookupDataCacheService lookupDataCacheService,
        StorageConnectionRepository storageConnectionRepository,
        StorageClientFactory storageClientFactory,
        @Qualifier("minioObjectStorageService") ObjectStorageService minioObjectStorageService,
        @Qualifier("s3ObjectStorageService") ObjectStorageService s3ObjectStorageService,
        @Qualifier("azureBlobObjectStorageService") ObjectStorageService azureBlobObjectStorageService) {
        this.lookupDataCacheService = lookupDataCacheService;
        this.storageConnectionRepository = storageConnectionRepository;
        this.storageClientFactory = storageClientFactory;
        Map<String, ObjectStorageService> byProvider = new HashMap<>();
        byProvider.put("MINIO", minioObjectStorageService);
        byProvider.put("S3", s3ObjectStorageService);
        byProvider.put("AZURE", azureBlobObjectStorageService);
        this.objectStorageServicesByProvider = byProvider;
    }

    @Override
    public List<BucketSummaryDto> listBuckets() {
        boolean isPlatformAdmin = TenantContext.isPlatformAdmin();
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
            .filter(connection -> isPlatformAdmin
                || Objects.equals(connection.getTenantId(), callerTenantId))
            .forEach(connection -> buckets.add(new BucketSummaryDto(
                connection.getConnectionName(),
                connection.getAlias(),
                connection.getProvider() == null ? null : connection.getProvider().name())));

        // BUCKET_LIST lookups are the older mechanism, kept working so buckets configured that
        // way (and the jobs already pointing at them) keep resolving. A storage connection with
        // the same alias wins, which is what makes migrating one bucket at a time safe.
        LookupDataDto bucketListParent = this.lookupDataCacheService.getParentLookupById(BUCKET_LIST);
        if (bucketListParent != null && bucketListParent.getChildren() != null) {
            bucketListParent.getChildren().stream()
                .filter(child -> isPlatformAdmin || Objects.equals(child.getTenantId(), callerTenantId))
                .filter(child -> buckets.stream().noneMatch(b -> b.getBucket().equals(child.getLookupValue())))
                .forEach(child -> buckets.add(new BucketSummaryDto(
                    child.getLookupType(), child.getLookupValue(), child.getDescription())));
        }
        return buckets;
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        int pageSize = maxKeys <= 0 ? DEFAULT_PAGE_SIZE : Math.min(maxKeys, MAX_PAGE_SIZE);
        return this.resolveService(bucket).listObjects(bucket, prefix, continuationToken, pageSize);
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        return this.resolveService(bucket).getObjectMetadata(bucket, key);
    }

    @Override
    // Same guard as fileChatExtract: callers explicitly handle a null metadata result, and the
    // cache rejects nulls, so without this an unreadable object throws instead of being reported.
    @Cacheable(value = "fileChatMetadata", key = "#bucket + ':' + #key", unless = "#result == null")
    public ObjectMetadataDto getObjectMetadataCached(String bucket, String key) {
        return this.getObjectMetadata(bucket, key);
    }

    @Override
    public ObjectContentDto previewObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        if (!ContentTypeUtil.isPreviewable(key)) {
            throw new IllegalArgumentException("Preview is not supported for this file type; use download instead.");
        }
        if (ContentTypeUtil.isPreviewableGzip(key)) {
            return this.previewGzip(bucket, key);
        }
        return this.resolveService(bucket).getObjectContent(bucket, key, rangeStart, rangeEnd);
    }

    /**
     * Serves a gzipped text file as its decompressed contents, typed by what's inside
     * ("audit.json.gz" is delivered as JSON). Handing the browser the raw gzip bytes would just
     * render as binary noise, and a byte range through a compressed stream is meaningless, so
     * this reads and inflates whole rather than honouring range requests.
     */
    private ObjectContentDto previewGzip(String bucket, String key) {
        ObjectContentDto compressed = this.resolveService(bucket).getObjectContent(bucket, key, null, null);
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
        return this.resolveService(bucket).getObjectContent(bucket, key, rangeStart, rangeEnd);
    }

    @Override
    public void uploadObject(String bucket, String prefix, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }

        String safeFileName = Paths.get(file.getOriginalFilename()).getFileName().toString();
        String key = this.normalizedPrefix(prefix) + safeFileName;
        String extension = ContentTypeUtil.extensionOf(safeFileName);
        if (!AudioTranscodeUtil.isAudioExtension(extension)) {
            try {
                this.resolveService(bucket).uploadObject(bucket, key, file.getInputStream(), file.getSize(), file.getContentType());
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
                this.resolveService(bucket).uploadObject(bucket, key, in, Files.size(uploadSource), ContentTypeUtil.contentTypeFor(key));
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

        this.resolveService(bucket).uploadObject(bucket, key, inputStream, size, contentType);
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
        this.resolveService(bucket).createFolder(bucket, folderKey);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        this.resolveService(bucket).deleteObject(bucket, key);
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("No keys given to delete.");
        }
        this.resolveService(bucket).deleteObjects(bucket, keys);
    }

    @Override
    public void deleteFolder(String bucket, String folderKey) {
        this.requireFolderKey(folderKey);
        this.resolveService(bucket).deleteFolder(bucket, folderKey);
    }

    @Override
    public void renameFolder(String bucket, String folderKey, String newFolderName) {
        this.requireFolderKey(folderKey);
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
        if (newPrefix.equals(folderKey)) {
            return;
        }
        this.resolveService(bucket).renameFolder(bucket, folderKey, newPrefix);
    }

    private void requireFolderKey(String folderKey) {
        if (folderKey == null || folderKey.trim().isEmpty() || !folderKey.endsWith("/")) {
            throw new IllegalArgumentException("A folder key must end with '/'.");
        }
    }

    private String normalizedPrefix(String prefix) {
        return prefix == null ? "" : prefix;
    }

    private ObjectStorageService resolveService(String bucket) {
        Optional<StorageConnection> connection = this.storageConnectionRepository.findByAliasAndStatus(bucket, Status.Active);
        if (connection.isPresent()) {
            StorageConnection storageConnection = connection.get();
            if (!TenantContext.isPlatformAdmin()
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

        BucketSummaryDto bucketSummary = this.listBuckets().stream()
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
