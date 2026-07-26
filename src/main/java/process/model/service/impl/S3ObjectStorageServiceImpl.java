package process.model.service.impl;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.service.ObjectStorageService;
import process.util.ContentTypeUtil;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * S3ObjectStorageServiceImpl - Bucket Browser's AWS S3 provider, talking to S3 through the
 * shared {@link S3Client} bean (lazy -- only initialized the first time a bucket actually
 * resolves to S3).
 * @author Nabeel Ahmed
 */
@Service("s3ObjectStorageService")
public class S3ObjectStorageServiceImpl implements ObjectStorageService {

    private final S3Client s3Client;

    public S3ObjectStorageServiceImpl(@Lazy S3Client s3Client) {
        this.s3Client = s3Client;
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        try {
            ListObjectsV2Request.Builder builder = ListObjectsV2Request.builder()
                .bucket(bucket)
                .delimiter("/")
                .maxKeys(maxKeys);
            if (prefix != null && !prefix.isEmpty()) {
                builder.prefix(prefix);
            }
            if (continuationToken != null && !continuationToken.isEmpty()) {
                builder.continuationToken(continuationToken);
            }
            ListObjectsV2Response response = this.s3Client.listObjectsV2(builder.build());

            List<ObjectSummaryDto> objects = new ArrayList<>();
            for (CommonPrefix commonPrefix : response.commonPrefixes()) {
                String key = commonPrefix.prefix();
                String trimmedKey = key.endsWith("/") ? key.substring(0, key.length() - 1) : key;
                objects.add(new ObjectSummaryDto(this.fileNameOf(trimmedKey), key, true, null, null));
            }
            for (S3Object s3Object : response.contents()) {
                String key = s3Object.key();
                if (key.equals(prefix)) {
                    // Zero-byte "folder placeholder" object some tools create for the prefix itself.
                    continue;
                }
                objects.add(new ObjectSummaryDto(
                    this.fileNameOf(key),
                    key,
                    false,
                    s3Object.size(),
                    s3Object.lastModified() != null ? s3Object.lastModified().toString() : null
                ));
            }
            String nextToken = Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
            return new BrowseObjectsResponseDto(objects, nextToken);
        } catch (Exception e) {
            throw new RuntimeException("Could not list S3 objects bucket=" + bucket + " prefix=" + prefix, e);
        }
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        try {
            HeadObjectResponse head = this.s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return new ObjectMetadataDto(
                this.fileNameOf(key),
                key,
                head.contentLength(),
                head.lastModified() != null ? head.lastModified().toString() : null,
                head.eTag(),
                head.contentType(),
                ContentTypeUtil.isPreviewable(key)
            );
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch S3 object metadata " + bucket + "/" + key, e);
        }
    }

    @Override
    public ObjectContentDto getObjectContent(String bucket, String key) {
        try {
            ResponseInputStream<GetObjectResponse> response =
                this.s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
            Long contentLength = response.response().contentLength();
            return new ObjectContentDto(response, ContentTypeUtil.contentTypeFor(key), contentLength != null ? contentLength : 0L, this.fileNameOf(key));
        } catch (Exception e) {
            throw new RuntimeException("Could not fetch S3 object content " + bucket + "/" + key, e);
        }
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream stream, long size, String contentType) {
        try {
            this.s3Client.putObject(
                PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                RequestBody.fromInputStream(stream, size));
        } catch (Exception e) {
            throw new RuntimeException("Could not upload S3 object " + bucket + "/" + key, e);
        }
    }

    @Override
    public void createFolder(String bucket, String folderKey) {
        try {
            this.s3Client.putObject(PutObjectRequest.builder().bucket(bucket).key(folderKey).build(), RequestBody.empty());
        } catch (Exception e) {
            throw new RuntimeException("Could not create S3 folder " + bucket + "/" + folderKey, e);
        }
    }

    @Override
    public void deleteObject(String bucket, String key) {
        try {
            this.s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (Exception e) {
            throw new RuntimeException("Could not delete S3 object " + bucket + "/" + key, e);
        }
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        try {
            // S3's DeleteObjects API rejects more than 1000 keys per request.
            for (int start = 0; start < keys.size(); start += 1000) {
                List<String> batch = keys.subList(start, Math.min(start + 1000, keys.size()));
                List<ObjectIdentifier> toDelete = batch.stream().map(key -> ObjectIdentifier.builder().key(key).build()).collect(Collectors.toList());
                this.s3Client.deleteObjects(
                    DeleteObjectsRequest.builder().bucket(bucket).delete(Delete.builder().objects(toDelete).build()).build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not bulk delete S3 objects bucket=" + bucket, e);
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
                this.s3Client.copyObject(
                    CopyObjectRequest.builder()
                        .sourceBucket(bucket).sourceKey(oldKey)
                        .destinationBucket(bucket).destinationKey(newKey)
                        .build());
            }
        } catch (Exception e) {
            throw new RuntimeException("Could not rename S3 folder " + bucket + "/" + oldPrefix + " -> " + newPrefix, e);
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
            for (S3Object s3Object : this.s3Client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build()).contents()) {
                keys.add(s3Object.key());
            }
            return keys;
        } catch (Exception e) {
            throw new RuntimeException("Could not list S3 objects under bucket=" + bucket + " prefix=" + prefix, e);
        }
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
