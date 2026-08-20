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
import process.model.dto.ObjectContentDto;
import process.model.dto.PdfHighlighterTaskDto;
import process.model.dto.ResponseDto;
import process.model.dto.SyncPdfHighlighterFieldsRequestDto;
import process.model.service.PdfHighlighterTaskService;
import process.util.ProcessUtil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@RestController
@CrossOrigin(origins = "*", exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH})
@RequestMapping(value = "/pdfHighlighter.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class PdfHighlighterTaskRestApi {

    private Logger logger = LoggerFactory.getLogger(PdfHighlighterTaskRestApi.class);

    private final PdfHighlighterTaskService pdfHighlighterTaskService;

    public PdfHighlighterTaskRestApi(PdfHighlighterTaskService pdfHighlighterTaskService) {
        this.pdfHighlighterTaskService = pdfHighlighterTaskService;
    }

    @RequestMapping(value = "/fetchAllPdfHighlighterTask", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllPdfHighlighterTask() {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.fetchAllPdfHighlighterTask(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllPdfHighlighterTask.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/fetchPdfHighlighterTaskById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchPdfHighlighterTaskById(
        @RequestParam Long pdfHighlighterTaskId) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.fetchPdfHighlighterTaskById(pdfHighlighterTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchPdfHighlighterTaskById.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/addPdfHighlighterTask", method = RequestMethod.POST)
    public ResponseEntity<?> addPdfHighlighterTask(
        @RequestBody PdfHighlighterTaskDto tempPdfHighlighterTask) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.addPdfHighlighterTask(tempPdfHighlighterTask), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addPdfHighlighterTask.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/updatePdfHighlighterTask", method = RequestMethod.PUT)
    public ResponseEntity<?> updatePdfHighlighterTask(
        @RequestBody PdfHighlighterTaskDto tempPdfHighlighterTask) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.updatePdfHighlighterTask(tempPdfHighlighterTask), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updatePdfHighlighterTask.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/deletePdfHighlighterTask", method = RequestMethod.DELETE)
    public ResponseEntity<?> deletePdfHighlighterTask(
        @RequestParam Long pdfHighlighterTaskId) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.deletePdfHighlighterTask(pdfHighlighterTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deletePdfHighlighterTask.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/fetchPdfHighlighterFields", method = RequestMethod.GET)
    public ResponseEntity<?> fetchPdfHighlighterFields(
        @RequestParam Long pdfHighlighterTaskId) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.fetchPdfHighlighterFields(pdfHighlighterTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchPdfHighlighterFields.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/syncPdfHighlighterFields", method = RequestMethod.POST)
    public ResponseEntity<?> syncPdfHighlighterFields(
        @RequestBody SyncPdfHighlighterFieldsRequestDto request) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.syncPdfHighlighterFields(request), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while syncPdfHighlighterFields.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/uploadPdfHighlighterFile", method = RequestMethod.POST)
    public ResponseEntity<?> uploadPdfHighlighterFile(
        @RequestParam Long pdfHighlighterTaskId,
        @RequestParam("file") MultipartFile file) {
        try {
            return new ResponseEntity<>(this.pdfHighlighterTaskService.uploadPdfHighlighterFile(pdfHighlighterTaskId, file), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadPdfHighlighterFile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/downloadPdfHighlighterFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadPdfHighlighterFile(
        @RequestParam Long pdfHighlighterTaskId) {
        try {
            ObjectContentDto content = this.pdfHighlighterTaskService.downloadPdfHighlighterFile(pdfHighlighterTaskId);
            String encodedFileName = URLEncoder.encode(content.getFileName(), StandardCharsets.UTF_8.name()).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encodedFileName);
            return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(content.getContentType()))
                .contentLength(content.getSize())
                .body(new InputStreamResource(content.getContent()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            logger.warn("downloadPdfHighlighterFile rejected pdfHighlighterTaskId={}: {}", pdfHighlighterTaskId, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while downloadPdfHighlighterFile pdfHighlighterTaskId={}", pdfHighlighterTaskId, ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}
