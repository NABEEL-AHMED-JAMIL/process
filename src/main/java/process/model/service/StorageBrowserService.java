package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import java.io.InputStream;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
public interface StorageBrowserService {

    List<BucketSummaryDto> listBuckets();

    BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys);

    ObjectMetadataDto getObjectMetadata(String bucket, String key);

    ObjectMetadataDto getObjectMetadataCached(String bucket, String key);

    ObjectContentDto previewObject(String bucket, String key, Long rangeStart, Long rangeEnd);

    ObjectContentDto downloadObject(String bucket, String key, Long rangeStart, Long rangeEnd);

    void uploadObject(String bucket, String prefix, MultipartFile file);

    void uploadObject(String bucket, String key, InputStream inputStream, long size, String contentType);

    void createFolder(String bucket, String prefix, String folderName);

    void deleteObject(String bucket, String key);

    void deleteObjects(String bucket, List<String> keys);

    void deleteFolder(String bucket, String folderKey);

    void renameFolder(String bucket, String folderKey, String newFolderName);

    /**
     * The three below are for application workflows, and they are trusted: they skip the check
     * that keeps a caller out of a platform bucket, because the workflow has already established
     * that the row the key comes from is the caller's -- a PDF highlighter task it owns, a Kafka
     * connection profile's truststore -- and builds the key itself from that row. Everything
     * above scopes a bucket and key the caller named; these must never be handed one.
     */
    void uploadForWorkflow(String bucket, String prefix, MultipartFile file);

    void uploadForWorkflow(String bucket, String key, InputStream inputStream, long size, String contentType);

    ObjectContentDto readForWorkflow(String bucket, String key);

}
