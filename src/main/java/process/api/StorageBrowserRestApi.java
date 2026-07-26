package process.api;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BulkDeleteRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.service.StorageBrowserService;
import process.util.ProcessUtil;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.function.BiFunction;

/**
 * Api use to browse buckets/objects across storage providers (MinIO / S3 / Azure Blob)
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/storage.json")
public class StorageBrowserRestApi {

    private Logger logger = LoggerFactory.getLogger(StorageBrowserRestApi.class);

    private final StorageBrowserService storageBrowserService;

    public StorageBrowserRestApi(StorageBrowserService storageBrowserService) {
        this.storageBrowserService = storageBrowserService;
    }

    /**
     * Api use to list the buckets available to browse (from the BUCKET_LIST lookup)
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/buckets", method = RequestMethod.GET)
    public ResponseEntity<?> buckets() {
        try {
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Buckets fetched successfully.", this.storageBrowserService.listBuckets()),
                HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetching buckets ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to list the immediate folders/objects under prefix, one page at a time
     * @param bucket
     * @param prefix
     * @param continuationToken cursor from a previous page's response, omit for the first page
     * @param maxKeys page size (default 50)
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/listObjects", method = RequestMethod.GET)
    public ResponseEntity<?> listObjects(
        @RequestParam String bucket,
        @RequestParam(required = false, defaultValue = "") String prefix,
        @RequestParam(required = false) String continuationToken,
        @RequestParam(required = false, defaultValue = "50") int maxKeys) {
        try {
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Objects fetched successfully.",
                    this.storageBrowserService.listObjects(bucket, prefix, continuationToken, maxKeys)),
                HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("listObjects rejected bucket={} prefix={}: {}", bucket, prefix, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while listObjects bucket={} prefix={}", bucket, prefix, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single object's metadata (side-panel "Object Info")
     * @param bucket
     * @param key
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/objectMetadata", method = RequestMethod.GET)
    public ResponseEntity<?> objectMetadata(
        @RequestParam String bucket,
        @RequestParam String key) {
        try {
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Object metadata fetched successfully.",
                    this.storageBrowserService.getObjectMetadata(bucket, key)),
                HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("objectMetadata rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while fetching object metadata bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to stream an object's content inline for preview -- json/csv/pdf only
     * @param bucket
     * @param key
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/previewObject", method = RequestMethod.GET)
    public ResponseEntity<?> previewObject(
        @RequestParam String bucket,
        @RequestParam String key) {
        return this.streamObject(bucket, key, "inline", this.storageBrowserService::previewObject);
    }

    /**
     * Api use to stream an object's content as a download -- any file type
     * @param bucket
     * @param key
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/downloadObject", method = RequestMethod.GET)
    public ResponseEntity<?> downloadObject(
        @RequestParam String bucket,
        @RequestParam String key) {
        return this.streamObject(bucket, key, "attachment", this.storageBrowserService::downloadObject);
    }

    /**
     * Api use to upload a file into the current folder
     * @param bucket
     * @param prefix current folder, omit/empty for the bucket root
     * @param file
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/uploadObject", method = RequestMethod.POST)
    public ResponseEntity<?> uploadObject(
        @RequestParam String bucket,
        @RequestParam(required = false, defaultValue = "") String prefix,
        @RequestParam("file") MultipartFile file) {
        try {
            this.storageBrowserService.uploadObject(bucket, prefix, file);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "File uploaded successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("uploadObject rejected bucket={} prefix={}: {}", bucket, prefix, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploading object bucket={} prefix={}", bucket, prefix, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to create a new sub-folder in the current folder
     * @param bucket
     * @param prefix current folder, omit/empty for the bucket root
     * @param folderName just the new folder's own name, not a path
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/createFolder", method = RequestMethod.POST)
    public ResponseEntity<?> createFolder(
        @RequestParam String bucket,
        @RequestParam(required = false, defaultValue = "") String prefix,
        @RequestParam String folderName) {
        try {
            this.storageBrowserService.createFolder(bucket, prefix, folderName);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Folder created successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("createFolder rejected bucket={} prefix={}: {}", bucket, prefix, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while creating folder bucket={} prefix={}", bucket, prefix, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to delete a single object
     * @param bucket
     * @param key
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteObject", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteObject(
        @RequestParam String bucket,
        @RequestParam String key) {
        try {
            this.storageBrowserService.deleteObject(bucket, key);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Object deleted successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("deleteObject rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while deleting object bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to delete multiple objects in one call (multi-select delete)
     * @param request
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteObjects", method = RequestMethod.POST)
    public ResponseEntity<?> deleteObjects(@RequestBody BulkDeleteRequestDto request) {
        try {
            this.storageBrowserService.deleteObjects(request.getBucket(), request.getKeys());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Objects deleted successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("deleteObjects rejected bucket={}: {}", request.getBucket(), ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while bulk deleting objects bucket={}", request.getBucket(), ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to recursively delete a folder and everything inside it
     * @param bucket
     * @param key the folder's key, must end with "/"
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteFolder", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteFolder(
        @RequestParam String bucket,
        @RequestParam String key) {
        try {
            this.storageBrowserService.deleteFolder(bucket, key);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Folder deleted successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("deleteFolder rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while deleting folder bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to rename a folder (recursive copy + delete under the hood)
     * @param bucket
     * @param key the folder's current key, must end with "/"
     * @param newFolderName just the new name, not a path
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/renameFolder", method = RequestMethod.POST)
    public ResponseEntity<?> renameFolder(
        @RequestParam String bucket,
        @RequestParam String key,
        @RequestParam String newFolderName) {
        try {
            this.storageBrowserService.renameFolder(bucket, key, newFolderName);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Folder renamed successfully."), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("renameFolder rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while renaming folder bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method use to stream an object's bytes back with the right content-type/disposition
     * headers, shared by previewObject and downloadObject
     * @param bucket
     * @param key
     * @param disposition "inline" or "attachment"
     * @param fetcher
     * @return ResponseEntity<?>
     * */
    private ResponseEntity<?> streamObject(String bucket, String key, String disposition,
        BiFunction<String, String, ObjectContentDto> fetcher) {
        try {
            ObjectContentDto content = fetcher.apply(bucket, key);
            byte[] bytes;
            try (InputStream stream = content.getContent()) {
                bytes = IOUtils.toByteArray(stream);
            }
            String encodedFileName = URLEncoder.encode(content.getFileName(), StandardCharsets.UTF_8.name()).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + encodedFileName);
            return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(bytes.length)
                .body(bytes);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("streamObject rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while streaming object bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
