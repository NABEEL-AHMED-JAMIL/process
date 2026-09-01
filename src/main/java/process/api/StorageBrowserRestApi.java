package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.BulkDeleteRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.service.StorageBrowserService;
import process.util.ProcessUtil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController

@CrossOrigin(origins = "*", exposedHeaders = {
    HttpHeaders.ACCEPT_RANGES, HttpHeaders.CONTENT_RANGE, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH
})
/**
 * @author Nabeel Ahmed
 * */
@RequestMapping(value = "/storage.json")
// The role is only the floor: a signed-in user, because every user uploads their own picture
// here and browses their own tenant's connections. Which bucket and which key they may actually
// reach is not a question a role can answer, and is settled per request in StorageBrowserService
// -- a platform-owned bucket (etl-avatar, etl-bucket) is refused to everyone but a platform
// admin, their own avatar aside. Workflow reads and writes into those buckets do not come
// through here at all; they use the service's readForWorkflow/uploadForWorkflow directly.
@PreAuthorize("hasRole('TENANT_USER')")
public class StorageBrowserRestApi {

    private Logger logger = LoggerFactory.getLogger(StorageBrowserRestApi.class);

    private final StorageBrowserService storageBrowserService;

    public StorageBrowserRestApi(StorageBrowserService storageBrowserService) {
        this.storageBrowserService = storageBrowserService;
    }

    @RequestMapping(value = "/buckets", method = RequestMethod.GET)
    public ResponseEntity<?> buckets() {
        try {
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Buckets fetched successfully.", this.storageBrowserService.listBuckets()),
                HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetching buckets ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/previewObject", method = RequestMethod.GET)
    public ResponseEntity<?> previewObject(
        @RequestParam String bucket,
        @RequestParam String key,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        return this.streamObject(bucket, key, "inline", rangeHeader, this.storageBrowserService::previewObject);
    }

    @RequestMapping(value = "/downloadObject", method = RequestMethod.GET)
    public ResponseEntity<?> downloadObject(
        @RequestParam String bucket,
        @RequestParam String key,
        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        return this.streamObject(bucket, key, "attachment", rangeHeader, this.storageBrowserService::downloadObject);
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

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
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private ResponseEntity<?> streamObject(String bucket, String key, String disposition, String rangeHeader,
        RangeContentFetcher fetcher) {
        try {
            long[] parsedRange = this.parseRange(rangeHeader);
            Long rangeStart = parsedRange != null ? parsedRange[0] : null;
            Long rangeEnd = parsedRange != null && parsedRange[1] != -1 ? parsedRange[1] : null;
            ObjectContentDto content = fetcher.fetch(bucket, key, rangeStart, rangeEnd);
            String encodedFileName = URLEncoder.encode(content.getFileName(), StandardCharsets.UTF_8.name()).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename*=UTF-8''" + encodedFileName);
            headers.add(HttpHeaders.ACCEPT_RANGES, "bytes");
            InputStreamResource body = new InputStreamResource(content.getContent());
            if (rangeStart != null) {
                long rangeEndInclusive = rangeStart + content.getSize() - 1;
                headers.add(HttpHeaders.CONTENT_RANGE, "bytes " + rangeStart + "-" + rangeEndInclusive + "/" + content.getTotalSize());
                return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .headers(headers)
                    .contentType(MediaType.parseMediaType(content.getContentType()))
                    .contentLength(content.getSize())
                    .body(body);
            }
            return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(content.getSize())
                .body(body);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("streamObject rejected bucket={} key={}: {}", bucket, key, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while streaming object bucket={} key={}", bucket, key, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private long[] parseRange(String rangeHeader) {
        if (rangeHeader == null || !rangeHeader.startsWith("bytes=")) {
            return null;
        }
        String spec = rangeHeader.substring("bytes=".length()).trim();
        int dash = spec.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        try {
            long start = Long.parseLong(spec.substring(0, dash));
            String endPart = spec.substring(dash + 1).trim();
            long end = endPart.isEmpty() ? -1 : Long.parseLong(endPart);
            if (start < 0 || (end != -1 && end < start)) {
                return null;
            }
            return new long[]{start, end};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @FunctionalInterface
    private interface RangeContentFetcher {
        ObjectContentDto fetch(String bucket, String key, Long rangeStart, Long rangeEnd);
    }

}
