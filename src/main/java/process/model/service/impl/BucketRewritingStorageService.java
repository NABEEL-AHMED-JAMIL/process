package process.model.service.impl;

import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.service.ObjectStorageService;
import java.io.InputStream;
import java.util.List;

/**
 * Forwards every call to a delegate, substituting the connection's real bucket/container name
 * for the alias the rest of the application passes around.
 *
 * A StorageConnection's alias is what appears in job_queue.bucket, document-converter tasks,
 * file chat, and every existing `bucket` parameter -- but it doesn't have to equal the actual
 * bucket name at the provider (and for FTP there is no bucket at all). Doing the swap in one
 * adapter keeps that translation out of all fifteen call sites in StorageBrowserServiceImpl.
 *
 * @author Nabeel Ahmed
 */
class BucketRewritingStorageService implements ObjectStorageService {

    private final ObjectStorageService delegate;
    private final String realBucket;

    BucketRewritingStorageService(ObjectStorageService delegate, String realBucket) {
        this.delegate = delegate;
        this.realBucket = realBucket;
    }

    @Override
    public BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys) {
        return this.delegate.listObjects(this.realBucket, prefix, continuationToken, maxKeys);
    }

    @Override
    public ObjectMetadataDto getObjectMetadata(String bucket, String key) {
        return this.delegate.getObjectMetadata(this.realBucket, key);
    }

    @Override
    public ObjectContentDto getObjectContent(String bucket, String key, Long rangeStart, Long rangeEnd) {
        return this.delegate.getObjectContent(this.realBucket, key, rangeStart, rangeEnd);
    }

    @Override
    public void uploadObject(String bucket, String key, InputStream stream, long size, String contentType) {
        this.delegate.uploadObject(this.realBucket, key, stream, size, contentType);
    }

    @Override
    public void createFolder(String bucket, String folderKey) {
        this.delegate.createFolder(this.realBucket, folderKey);
    }

    @Override
    public void deleteObject(String bucket, String key) {
        this.delegate.deleteObject(this.realBucket, key);
    }

    @Override
    public void deleteObjects(String bucket, List<String> keys) {
        this.delegate.deleteObjects(this.realBucket, keys);
    }

    @Override
    public void deleteFolder(String bucket, String folderPrefix) {
        this.delegate.deleteFolder(this.realBucket, folderPrefix);
    }

    @Override
    public void renameFolder(String bucket, String oldPrefix, String newPrefix) {
        this.delegate.renameFolder(this.realBucket, oldPrefix, newPrefix);
    }

}
