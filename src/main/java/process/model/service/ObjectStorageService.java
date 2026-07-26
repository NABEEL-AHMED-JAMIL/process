package process.model.service;

import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import java.io.InputStream;
import java.util.List;

/**
 * ObjectStorageService - one implementation per cloud provider (MinIO, S3, Azure Blob), all
 * behind this same interface so the Bucket Browser can talk to whichever provider a bucket
 * is configured for without knowing which SDK is actually involved.
 * @author Nabeel Ahmed
 */
public interface ObjectStorageService {

    /**
     * Method use to list the immediate folders/objects under prefix (non-recursive, delimiter
     * on "/"), one page at a time.
     * @param bucket
     * @param prefix
     * @param continuationToken opaque cursor from a previous page's response, null for the first page
     * @param maxKeys page size
     * @return BrowseObjectsResponseDto
     * */
    BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys);

    /**
     * Method use to fetch a single object's metadata
     * @param bucket
     * @param key
     * @return ObjectMetadataDto
     * */
    ObjectMetadataDto getObjectMetadata(String bucket, String key);

    /**
     * Method use to fetch an object's raw content for preview/download
     * @param bucket
     * @param key
     * @return ObjectContentDto
     * */
    ObjectContentDto getObjectContent(String bucket, String key);

    /**
     * Method use to upload a file's content to bucket/key
     * @param bucket
     * @param key
     * @param stream
     * @param size
     * @param contentType
     * */
    void uploadObject(String bucket, String key, InputStream stream, long size, String contentType);

    /**
     * Method use to create a folder -- an empty marker object whose key ends in "/"
     * @param bucket
     * @param folderKey key already ending in "/", e.g. "invoices/2026/"
     * */
    void createFolder(String bucket, String folderKey);

    /**
     * Method use to delete a single object
     * @param bucket
     * @param key
     * */
    void deleteObject(String bucket, String key);

    /**
     * Method use to delete multiple objects in one call
     * @param bucket
     * @param keys
     * */
    void deleteObjects(String bucket, List<String> keys);

    /**
     * Method use to recursively delete a folder -- every object whose key starts with
     * folderPrefix, including the folder's own marker object
     * @param bucket
     * @param folderPrefix key ending in "/", e.g. "invoices/2026/"
     * */
    void deleteFolder(String bucket, String folderPrefix);

    /**
     * Method use to recursively rename a folder -- copies every object under oldPrefix to the
     * same relative path under newPrefix, then deletes everything under oldPrefix. There's no
     * native "rename" in object storage; this is copy + delete.
     * @param bucket
     * @param oldPrefix key ending in "/", e.g. "invoices/2025/"
     * @param newPrefix key ending in "/", e.g. "invoices/2025-archived/"
     * */
    void renameFolder(String bucket, String oldPrefix, String newPrefix);

}
