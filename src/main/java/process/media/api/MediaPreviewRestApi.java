package process.media.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.media.preview.ObjectPreviewServiceImpl;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * Preview -- a page of a table, a document rendered to PDF, an archive's listing. Media &
 * Documents' (ADR-012), moved out of StorageBrowserRestApi on the same /storage.json paths so the
 * console did not change (MIG-40). The gateway will route these three paths to Media (MIG-48).
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*", exposedHeaders = {
    HttpHeaders.ACCEPT_RANGES, HttpHeaders.CONTENT_RANGE, HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH
})
@RequestMapping(value = "/storage.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class MediaPreviewRestApi {

    private final Logger logger = LoggerFactory.getLogger(MediaPreviewRestApi.class);

    private final ObjectPreviewServiceImpl preview;

    public MediaPreviewRestApi(ObjectPreviewServiceImpl preview) {
        this.preview = preview;
    }

    /** A page of a tabular object -- CSV/TSV, a sheet of a workbook, parquet, JSON lines, a JSON array. */
    @RequestMapping(value = "/previewTable", method = RequestMethod.GET)
    public ResponseEntity<?> previewTable(@RequestParam String bucket, @RequestParam String key,
        @RequestParam(required = false) String sheet, @RequestParam(required = false) Integer offset,
        @RequestParam(required = false) Integer limit) {
        try {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Table read.", this.preview.table(bucket, key, sheet, offset, limit)), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.OK);
        } catch (Exception ex) {
            logger.warn("previewTable failed for {}/{}: {}", bucket, key, ex.toString());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "This file could not be read as a table: " + ex.getMessage()), HttpStatus.OK);
        }
    }

    /** A document (docx, rtf, odt, pptx, html, ...) rendered to PDF for the viewer. */
    @RequestMapping(value = "/previewDocument", method = RequestMethod.GET)
    public ResponseEntity<?> previewDocument(@RequestParam String bucket, @RequestParam String key) {
        try {
            byte[] pdf = this.preview.document(bucket, key);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(pdf.length).body(pdf);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.warn("previewDocument failed for {}/{}: {}", bucket, key, ex.toString());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "This document could not be rendered."), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** What an archive holds, without downloading it. */
    @RequestMapping(value = "/previewArchive", method = RequestMethod.GET)
    public ResponseEntity<?> previewArchive(@RequestParam String bucket, @RequestParam String key) {
        try {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Archive listed.", this.preview.archive(bucket, key)), HttpStatus.OK);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, ex.getMessage()), HttpStatus.OK);
        } catch (Exception ex) {
            logger.warn("previewArchive failed for {}/{}: {}", bucket, key, ex.toString());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR, "This archive could not be read: " + ex.getMessage()), HttpStatus.OK);
        }
    }
}
