package process.model.service.impl;

import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.RemoveObjectsArgs;
import io.minio.Result;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.DeleteObject;
import io.minio.messages.Item;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.service.ObjectStorageService;
import process.util.ContentTypeUtil;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MinioObjectStorageServiceImpl - Bucket Browser's MinIO provider, talking to MinIO through
 * the shared {@link MinioClient} bean (lazy -- only initialized the first time a bucket
 * actually resolves to MINIO).
 * @author Nabeel Ahmed
 */
@Service("minioObjectStorageService")
public class MinioObjectStorageServiceImpl implements ObjectStorageService {

    private final MinioClient minioClient;

    public MinioObjectStorageServiceImpl(@Lazy MinioClient minioClient) {
        this.minioClient = minioClient;
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        try {
            // Over-fetch by one so we can tell whether there's a next page without a second round trip.
            int fetchLimit = maxKeys + 1;
            ListObjectsArgs.Builder builder = ListObjectsArgs.builder()
                .bucket(bucket)
                .recursive(false)
                .maxKeys(fetchLimit);
            if (prefix != null && !prefix.isEmpty()) {
                builder.prefix(prefix);
            }
            if (continuationToken != null && !continuationToken.isEmpty()) {
                builder.startAfter(continuationToken);
            }
            List<Item> items = new ArrayList<>();
            for (Result<Item> result : this.minioClient.listObjects(builder.build())) {
                items.add(result.get());
                if (items.size() >= fetchLimit) {
                    break;
                }
            }
            boolean hasMore = items.size() > maxKeys;
            List<Item> page = hasMore ? items.subList(0, maxKeys) : items;
            List<ObjectSummaryDto> objects = page.stream()
                // Skip the empty marker object a created folder leaves at exactly its own prefix.
                .filter(item -> !item.objectName().equals(prefix))
                .map(this::toObjectSummaryDto)
                .collect(Collectors.toList());
            String nextToken = hasMore ? page.get(page.size() - 1).objectName() : null;
            return new BrowseObjectsResponseDto(objects, nextToken);
        } catch (Exception e) {
            throw new RuntimeException("Could not list MinIO objects bucket=" + bucket + " prefix=" + prefix, e);
        }
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        try {
            StatObjectResponse stat = this.minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            return new ObjectMetadataDto(
                this.fileNameOf(key),
                key,
                stat.size(),
                stat.lastModified() != null ? stat.lastModified().toString() : null,
                stat.etag(),
                stat.contentType(),
                ContentTypeUtil.isPreviewable(key)
            );
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch MinIO object metadata " + bucket + "/" + key, e);
        }
    }

    @Override
    public ObjectContentDto getObjectContent(String bucket, String key) {
        try {
            StatObjectResponse stat = this.minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build());
            io.minio.GetObjectResponse response = this.minioClient.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
            return new ObjectContentDto(response, ContentTypeUtil.contentTypeFor(key), stat.size(), this.fileNameOf(key));
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch MinIO object content " + bucket + "/" + key, e);
        }
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            this.minioClient.putObject(
                PutObjectArgs.builder().bucket(bucket).object(key).stream(stream, size, -1).contentType(contentType).build());
        } catch (Exception e) {
            throw new RuntimeException("Could not upload MinIO object " + bucket + "/" + key, e);
        }
    }

    @Override
    public void createFolder(String bucket, String folderKey) {
        try {
            this.minioClient.putObject(
                PutObjectArgs.builder().bucket(bucket).object(folderKey).stream(new ByteArrayInputStream(new byte[0]), 0, -1).build());
        } catch (Exception e) {
            throw new RuntimeException("Could not create MinIO folder " + bucket + "/" + folderKey, e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            this.minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception e) {
            throw new RuntimeException("Could not delete MinIO object " + bucket + "/" + key, e);
        }
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        try {
            List<DeleteObject> toDelete = keys.stream().map(DeleteObject::new).collect(Collectors.toList());
            for (Result<io.minio.messages.DeleteError> result
                : this.minioClient.removeObjects(RemoveObjectsArgs.builder().bucket(bucket).objects(toDelete).build())) {
                io.minio.messages.DeleteError error = result.get();
                throw new RuntimeException("Could not delete MinIO object " + bucket + "/" + error.objectName() + ": " + error.message());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Could not bulk delete MinIO objects bucket=" + bucket, e);
        }
    }

    @Override
    public void deleteFolder(String bucket, String folderPrefix) {
        List<String> keys = this.listAllKeysUnderPrefix(bucket, folderPrefix);
        if (!keys.isEmpty()) {
            this.deleteObjects(bucket, keys);
        }
    }

    @Override
    public void renameFolder(String bucket, String oldPrefix, String newPrefix) {
        List<String> oldKeys = this.listAllKeysUnderPrefix(bucket, oldPrefix);
        try {
            for (String oldKey : oldKeys) {
                String newKey = newPrefix + oldKey.substring(oldPrefix.length());
                this.minioClient.copyObject(
                    CopyObjectArgs.builder()
                        .bucket(bucket)
                        .object(newKey)
                        .source(CopySource.builder().bucket(bucket).object(oldKey).build())
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not rename MinIO folder " + bucket + "/" + oldPrefix + " -> " + newPrefix, e);
        }
        if (!oldKeys.isEmpty()) {
            this.deleteObjects(bucket, oldKeys);
        }
    }

    /**
     * Method use to list every object key under prefix, recursively, across all pages
     * @param bucket
     * @param prefix
     * @return List of full object keys
     * */
    private List<String> listAllKeysUnderPrefix(String bucket, String prefix) {
        try {
            List<String> keys = new ArrayList<>();
            ListObjectsArgs args = ListObjectsArgs.builder().bucket(bucket).prefix(prefix).recursive(true).build();
            for (Result<Item> result : this.minioClient.listObjects(args)) {
                keys.add(result.get().objectName());
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Could not list MinIO objects under bucket=" + bucket + " prefix=" + prefix, e);
        }
    }

    /**
     * Method use to convert a MinIO Item (folder or object) into an ObjectSummaryDto
     * @param item
     * @return ObjectSummaryDto
     * */
    private ObjectSummaryDto toObjectSummaryDto(Item item) {
        String key = item.objectName();
        boolean isFolder = item.isDir();
        String trimmedKey = isFolder && key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
        return new ObjectSummaryDto(
            this.fileNameOf(trimmedKey),
            key,
            isFolder,
            isFolder ? null : item.size(),
            isFolder || item.lastModified() == null ? null : item.lastModified().toString()
        );
    }

    /**
     * Method use to get the last path segment of a key/prefix
     * @param key
     * @return String
     * */
    private String fileNameOf(String key) {
        return key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
    }

}
