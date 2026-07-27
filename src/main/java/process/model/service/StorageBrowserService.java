package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import java.util.List;

/**
 * StorageBrowserService - the Bucket Browser's entry point: resolves a bucket to its
 * configured {@link ObjectStorageService} provider (via the BUCKET_LIST lookup) and
 * delegates, plus enforces the preview/download content-type policy.
 * @author Nabeel Ahmed
 */
public interface StorageBrowserService {

    /**
     * Method use to list the buckets available to browse, from the BUCKET_LIST lookup
     * @return List of BucketSummaryDto
     * */
    List<BucketSummaryDto> listBuckets();

    /**
     * Method use to list the immediate folders/objects under prefix for a bucket
     * @param bucket
     * @param prefix
     * @param continuationToken
     * @param maxKeys
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
     * Method use to fetch an object's content for inline preview -- only for
     * json/csv/txt/pdf/mp3/m4a/mp4/image; throws for any other type
     * @param bucket
     * @param key
     * @return ObjectContentDto
     * */
    ObjectContentDto previewObject(String bucket, String key);

    /**
     * Method use to fetch an object's content for download -- any file type
     * @param bucket
     * @param key
     * @return ObjectContentDto
     * */
    ObjectContentDto downloadObject(String bucket, String key);

    /**
     * Method use to upload a file under prefix (the current folder)
     * @param bucket
     * @param prefix current folder, e.g. "" or "invoices/"
     * @param file
     * */
    void uploadObject(String bucket, String prefix, MultipartFile file);

    /**
     * Method use to create a new sub-folder under prefix (the current folder)
     * @param bucket
     * @param prefix current folder, e.g. "" or "invoices/"
     * @param folderName just the new folder's own name, e.g. "2026" -- not a path
     * */
    void createFolder(String bucket, String prefix, String folderName);

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
     * Method use to recursively delete a folder and everything inside it
     * @param bucket
     * @param folderKey the folder's key, must end with "/"
     * */
    void deleteFolder(String bucket, String folderKey);

    /**
     * Method use to rename a folder (recursive copy + delete under the hood) -- the folder
     * stays in the same parent, only its own name segment changes
     * @param bucket
     * @param folderKey the folder's current key, must end with "/"
     * @param newFolderName just the new name, e.g. "2026" -- not a path
     * */
    void renameFolder(String bucket, String folderKey, String newFolderName);

}
