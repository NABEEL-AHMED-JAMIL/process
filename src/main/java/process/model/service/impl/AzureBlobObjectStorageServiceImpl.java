package process.model.service.impl;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.PagedResponse;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobProperties;
import com.azure.storage.blob.models.BlobRange;
import com.azure.storage.blob.models.ListBlobsOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.service.ObjectStorageService;
import process.util.ContentTypeUtil;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

@Service("azureBlobObjectStorageService")
public class AzureBlobObjectStorageServiceImpl implements ObjectStorageService {

    private final ObjectProvider<BlobServiceClient> blobServiceClientProvider;

    public AzureBlobObjectStorageServiceImpl(ObjectProvider<BlobServiceClient> blobServiceClientProvider) {
        this.blobServiceClientProvider = blobServiceClientProvider;
    }

    private BlobServiceClient blobServiceClient() {
        return this.blobServiceClientProvider.getObject();
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        try {
            BlobContainerClient containerClient = this.blobServiceClient().getBlobContainerClient(bucket);
            ListBlobsOptions options = new ListBlobsOptions();
            if (prefix != null && !prefix.isEmpty()) {
                options.setPrefix(prefix);
            }
            PagedIterable<BlobItem> pagedIterable = containerClient.listBlobsByHierarchy("/", options, null);
            Iterable<PagedResponse<BlobItem>> pages = continuationToken != null && !continuationToken.isEmpty()
                ? pagedIterable.iterableByPage(continuationToken, maxKeys)
                : pagedIterable.iterableByPage(maxKeys);
            Iterator<PagedResponse<BlobItem>> pageIterator = pages.iterator();
            if (!pageIterator.hasNext()) {
                return new BrowseObjectsResponseDto(new ArrayList<>(), null);
            }
            PagedResponse<BlobItem> page = pageIterator.next();
            List<ObjectSummaryDto> objects = page.getValue().stream()

                .filter(item -> !item.getName().equals(prefix))
                .map(this::toObjectSummaryDto)
                .collect(Collectors.toList());
            String nextToken = page.getContinuationToken();
            return new BrowseObjectsResponseDto(objects, nextToken != null && !nextToken.isEmpty() ? nextToken : null);
        } catch (Exception e) {
            throw new RuntimeException("Could not list Azure blobs container=" + bucket + " prefix=" + prefix, e);
        }
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        try {
            BlobProperties properties = this.blobClient(bucket, key).getProperties();
            return new ObjectMetadataDto(
                this.fileNameOf(key),
                key,
                properties.getBlobSize(),
                properties.getLastModified() != null ? properties.getLastModified().toString() : null,
                properties.getETag(),
                properties.getContentType(),
                ContentTypeUtil.isPreviewable(key)
            );
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch Azure blob metadata " + bucket + "/" + key, e);
        }
    }

    @Override
    public ObjectContentDto getObjectContent(String bucket, String key, Long rangeStart, Long rangeEnd) {
        try {
            BlobClient blobClient = this.blobClient(bucket, key);
            long totalSize = blobClient.getProperties().getBlobSize();
            long contentLength = totalSize;
            InputStream stream;
            if (rangeStart != null) {
                long end = rangeEnd != null ? Math.min(rangeEnd, totalSize - 1) : totalSize - 1;
                contentLength = end - rangeStart + 1;
                stream = blobClient.openInputStream(new BlobRange(rangeStart, contentLength), null);
            } else {
                stream = blobClient.openInputStream();
            }
            return new ObjectContentDto(stream, ContentTypeUtil.contentTypeFor(key), contentLength, totalSize, this.fileNameOf(key));
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch Azure blob content " + bucket + "/" + key, e);
        }
    }

    private BlobClient blobClient(String bucket, String key) {
        return this.blobServiceClient().getBlobContainerClient(bucket).getBlobClient(key);
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            BlobClient blobClient = this.blobClient(bucket, key);
            blobClient.upload(stream, size, true);
            if (contentType != null && !contentType.isEmpty()) {
                blobClient.setHttpHeaders(new BlobHttpHeaders().setContentType(contentType));
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not upload Azure blob " + bucket + "/" + key, e);
        }
    }

    @Override
    public void createFolder(String bucket, String folderKey) {
        try {
            this.blobClient(bucket, folderKey).upload(new java.io.ByteArrayInputStream(new byte[0]), 0, true);
        } catch (Exception e) {
            throw new RuntimeException("Could not create Azure folder " + bucket + "/" + folderKey, e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            this.blobClient(bucket, key).deleteIfExists();
        } catch (Exception e) {
            throw new RuntimeException("Could not delete Azure blob " + bucket + "/" + key, e);
        }
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        for (String key : keys) {
            this.deleteObject(bucket, key);
        }
    }

    @Override
    public void deleteFolder(String bucket, String folderPrefix) {
        List<String> keys = this.listAllKeysUnderPrefix(bucket, folderPrefix);
        this.deleteObjects(bucket, keys);
    }

    @Override
    public void renameFolder(String bucket, String oldPrefix, String newPrefix) {
        List<String> oldKeys = this.listAllKeysUnderPrefix(bucket, oldPrefix);
        try {
            for (String oldKey : oldKeys) {
                String newKey = newPrefix + oldKey.substring(oldPrefix.length());

                try (InputStream stream = this.blobClient(bucket, oldKey).openInputStream()) {
                    this.blobClient(bucket, newKey).upload(stream);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not rename Azure folder " + bucket + "/" + oldPrefix + " -> " + newPrefix, e);
        }
        this.deleteObjects(bucket, oldKeys);
    }

    private List<String> listAllKeysUnderPrefix(String bucket, String prefix) {
        try {
            ListBlobsOptions options = new ListBlobsOptions().setPrefix(prefix);
            List<String> keys = new ArrayList<>();
            for (BlobItem item : this.blobServiceClient().getBlobContainerClient(bucket).listBlobs(options, null)) {
                keys.add(item.getName());
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Could not list Azure blobs under container=" + bucket + " prefix=" + prefix, e);
        }
    }

    private ObjectSummaryDto toObjectSummaryDto(BlobItem item) {
        String name = item.getName();
        boolean isFolder = Boolean.TRUE.equals(item.isPrefix());
        String trimmedKey = isFolder && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        Long size = isFolder || item.getProperties() == null ? null : item.getProperties().getContentLength();
        String lastModified = isFolder || item.getProperties() == null || item.getProperties().getLastModified() == null
            ? null
            : item.getProperties().getLastModified().toString();
        String etag = isFolder || item.getProperties() == null ? null : this.stripQuotes(item.getProperties().getETag());
        String contentType = isFolder ? null : ContentTypeUtil.contentTypeFor(name);
        return new ObjectSummaryDto(this.fileNameOf(trimmedKey), name, isFolder, size, lastModified, etag, contentType);
    }

    private String stripQuotes(String etag) {
        return etag != null ? etag.replace("\"", "") : null;
    }

    private String fileNameOf(String key) {
        return key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
    }

}
