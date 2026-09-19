package process.model.service;

import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import java.io.InputStream;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
public interface ObjectStorageService {

    BrowseObjectsResponseDto listObjects(String bucket, String prefix, String continuationToken, int maxKeys);

    ObjectMetadataDto getObjectMetadata(String bucket, String key);

    ObjectContentDto getObjectContent(String bucket, String key, Long rangeStart, Long rangeEnd);

    void uploadObject(String bucket, String key, InputStream stream, long size, String contentType);

    void createFolder(String bucket, String folderKey);

    void deleteObject(String bucket, String key);

    void deleteObjects(String bucket, List<String> keys);

    void deleteFolder(String bucket, String folderPrefix);

    void renameFolder(String bucket, String oldPrefix, String newPrefix);

    /**
     * Every object under a prefix, with its size, however deep -- no folder rows, no paging.
     * What a measurement or a folder delete needs; a browse wants {@link #listObjects}.
     * Stops at {@code max} objects, so a bucket of millions is a warning rather than a wait.
     */
    default List<ObjectSummaryDto> listAllObjects(String bucket, String prefix, int max) {
        throw new UnsupportedOperationException("This store cannot be listed whole.");
    }

}
