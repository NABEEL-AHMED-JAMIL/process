package process.storage.remote;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.RequestBody;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.service.StorageBrowserService;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The guarded storage API, answered by storage-service (MIG-183) as the signed-in user: what file
 * chat and report export call from inside a request (analytics export left with Analytics). Storage applies every guard;
 * this only carries the user's token there and the answer back. fileChatMetadata stays here, with
 * file chat, keyed as before by who asked.
 */
public class HttpStorageBrowser implements StorageBrowserService {

    private final StorageServiceClient storage;

    public HttpStorageBrowser(StorageServiceClient storage) {
        this.storage = storage;
    }

    @Override
    public List<BucketSummaryDto> listBuckets() {
        JsonNode data = this.storage.guardedGet("/buckets", null);
        return data == null ? Collections.emptyList()
            : this.storage.json().convertValue(data, new TypeReference<List<BucketSummaryDto>>() { });
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        Map<String, String> query = query("bucket", bucket, "prefix", prefix == null ? "" : prefix);
        query.put("continuationToken", continuationToken);
        query.put("maxKeys", String.valueOf(maxKeys));
        return this.storage.json().convertValue(this.storage.guardedGet("/listObjects", query), BrowseObjectsResponseDto.class);
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        JsonNode data = this.storage.guardedGet("/objectMetadata", query("bucket", bucket, "key", key));
        return data == null || data.isNull() ? null : this.storage.json().convertValue(data, ObjectMetadataDto.class);
    }

    @Override
    @Cacheable(value = "fileChatMetadata",
        key = "T(process.security.TenantContext).getTenantId() + ':'"
            + " + T(process.security.TenantContext).getAppUserId() + ':' + #bucket + ':' + #key",
        unless = "#result == null")
    public ObjectMetadataDto getObjectMetadataCached(String bucket, String key) {
        return this.getObjectMetadata(bucket, key);
    }

    @Override
    public ObjectContentDto previewObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        return this.storage.guardedStream("/previewObject", query("bucket", bucket, "key", key), range(rangeStart, rangeEnd));
    }

    @Override
    public ObjectContentDto downloadObject(String bucket, String key, Long rangeStart, Long rangeEnd) {
        return this.storage.guardedStream("/downloadObject", query("bucket", bucket, "key", key), range(rangeStart, rangeEnd));
    }

    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void uploadObject(String bucket, String prefix, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty.");
        }
        try {
            this.storage.guardedUpload(bucket, prefix, file.getOriginalFilename(), file.getInputStream(), file.getSize(),
                file.getContentType());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read uploaded file " + file.getOriginalFilename(), e);
        }
    }

    /** A key the caller composed: sent as the multipart upload of its last segment into its folder. */
    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void uploadObject(String bucket, String key, InputStream inputStream, long size, String contentType) {
        if (key == null || key.isEmpty() || key.endsWith("/")) {
            throw new IllegalArgumentException("Invalid key: " + key + ".");
        }
        int slash = key.lastIndexOf('/');
        this.storage.guardedUpload(bucket, slash >= 0 ? key.substring(0, slash + 1) : "", key.substring(slash + 1),
            inputStream, size, contentType);
    }

    @Override
    public void createFolder(String bucket, String prefix, String folderName) {
        Map<String, String> query = query("bucket", bucket, "prefix", prefix == null ? "" : prefix);
        query.put("folderName", folderName);
        this.storage.guardedSend("POST", "/createFolder", query, RequestBody.create(new byte[0], null));
    }

    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void deleteObject(String bucket, String key) {
        this.storage.guardedSend("DELETE", "/deleteObject", query("bucket", bucket, "key", key), null);
    }

    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void deleteObjects(String bucket, List<String> keys) {
        Map<String, Object> body = new HashMap<>();
        body.put("bucket", bucket);
        body.put("keys", keys);
        this.storage.guardedSend("POST", "/deleteObjects", null, this.storage.jsonBody(body));
    }

    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void deleteFolder(String bucket, String folderKey) {
        this.storage.guardedSend("DELETE", "/deleteFolder", query("bucket", bucket, "key", folderKey), null);
    }

    @Override
    @CacheEvict(value = "fileChatMetadata", allEntries = true)
    public void renameFolder(String bucket, String folderKey, String newFolderName) {
        Map<String, String> query = query("bucket", bucket, "key", folderKey);
        query.put("newFolderName", newFolderName);
        this.storage.guardedSend("POST", "/renameFolder", query, RequestBody.create(new byte[0], null));
    }

    private static Map<String, String> query(String k1, String v1, String k2, String v2) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put(k1, v1);
        query.put(k2, v2);
        return query;
    }

    private static String range(Long start, Long end) {
        return start == null ? null : "bytes=" + start + "-" + (end == null ? "" : end);
    }
}
