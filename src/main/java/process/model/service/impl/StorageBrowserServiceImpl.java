package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.LookupDataDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.service.ObjectStorageService;
import process.model.service.StorageBrowserService;
import process.util.AudioTranscodeUtil;
import process.util.ContentTypeUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * StorageBrowserServiceImpl - the Bucket Browser's entry point. Buckets are configured under
 * the BUCKET_LIST parent lookup: each child's lookupType is the display label, lookupValue is
 * the real bucket/container name, and description is the storage provider (MINIO/S3/AZURE).
 * @author Nabeel Ahmed
 */
@Service
public class StorageBrowserServiceImpl implements StorageBrowserService {

    private static final Logger logger = LoggerFactory.getLogger(StorageBrowserServiceImpl.class);
    private static final String BUCKET_LIST = "BUCKET_LIST";
    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 500;

    private final LookupDataCacheService lookupDataCacheService;
    private final Map<String, ObjectStorageService> objectStorageServicesByProvider;

    public StorageBrowserServiceImpl(
        LookupDataCacheService lookupDataCacheService,
        @Qualifier("minioObjectStorageService") ObjectStorageService minioObjectStorageService,
        @Qualifier("s3ObjectStorageService") ObjectStorageService s3ObjectStorageService,
        @Qualifier("azureBlobObjectStorageService") ObjectStorageService azureBlobObjectStorageService) {
        this.lookupDataCacheService = lookupDataCacheService;
        Map<String, ObjectStorageService> byProvider = new HashMap<>();
        byProvider.put("MINIO", minioObjectStorageService);
        byProvider.put("S3", s3ObjectStorageService);
        byProvider.put("AZURE", azureBlobObjectStorageService);
        this.objectStorageServicesByProvider = byProvider;
    }

    @Override
    public List<BucketSummaryDto> listBuckets() {
        LookupDataDto bucketListParent = this.lookupDataCacheService.getParentLookupById(BUCKET_LIST);
        if (bucketListParent == null || bucketListParent.getChildren() == null) {
            return Collections.emptyList();
        }
        return bucketListParent.getChildren().stream()
            .map(child -> new BucketSummaryDto(child.getLookupType(), child.getLookupValue(), child.getDescription()))
            .collect(Collectors.toList());
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
    public ObjectContentDto previewObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        if (!ContentTypeUtil.isPreviewable(key)) {
            throw new IllegalArgumentException("Preview is not supported for this file type; use download instead.");
        }
        return this.resolveService(bucket).getObjectContent(bucket, key, rangeStart, rangeEnd);
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
        // Basename only, so a crafted filename can't inject extra "/" segments into the object key.
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
        // Audio uploads may be ALAC-encoded, which no major browser can decode for inline
        // preview -- transcode to AAC (or leave alone if it's already browser-safe) before
        // it lands in the bucket.
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
    public void createFolder(String bucket, String prefix, String folderName) {
        if (folderName == null || folderName.trim().isEmpty()) {
            throw new IllegalArgumentException("Folder name is required.");
        }
        // A folder name is a single new segment, not a path -- strip slashes so it can't escape the current folder.
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
        // Rename only changes the folder's own last segment -- keep the same parent.
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

    /**
     * Method use to resolve a bucket to its configured ObjectStorageService provider
     * @param bucket
     * @return ObjectStorageService
     * */
    private ObjectStorageService resolveService(String bucket) {
        BucketSummaryDto bucketSummary = this.listBuckets().stream()
            .filter(b -> bucket.equals(b.getBucket()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown bucket: " + bucket + ". Add it under the BUCKET_LIST lookup first."));
        String provider = bucketSummary.getProvider();
        ObjectStorageService service = provider != null
            ? this.objectStorageServicesByProvider.get(provider.trim().toUpperCase())
            : null;
        if (service == null) {
            throw new IllegalStateException(
                "Unsupported/missing storage provider '" + provider + "' for bucket " + bucket
                    + ". Set the BUCKET_LIST lookup entry's description to MINIO, S3, or AZURE.");
        }
        return service;
    }

}
