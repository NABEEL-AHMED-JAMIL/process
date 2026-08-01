package process.model.service;

import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ObjectContentDto;
import process.model.dto.PdfHighlighterTaskDto;
import process.model.dto.ResponseDto;
import process.model.dto.SyncPdfHighlighterFieldsRequestDto;

/**
 * @author Nabeel Ahmed
 */
public interface PdfHighlighterTaskService {

    public ResponseDto fetchAllPdfHighlighterTask() throws Exception;

    public ResponseDto fetchPdfHighlighterTaskById(Long pdfHighlighterTaskId) throws Exception;

    public ResponseDto addPdfHighlighterTask(PdfHighlighterTaskDto pdfHighlighterTaskDto) throws Exception;

    public ResponseDto updatePdfHighlighterTask(PdfHighlighterTaskDto pdfHighlighterTaskDto) throws Exception;

    public ResponseDto deletePdfHighlighterTask(Long pdfHighlighterTaskId) throws Exception;

    public ResponseDto fetchPdfHighlighterFields(Long pdfHighlighterTaskId) throws Exception;

    public ResponseDto syncPdfHighlighterFields(SyncPdfHighlighterFieldsRequestDto request) throws Exception;

    public ResponseDto uploadPdfHighlighterFile(Long pdfHighlighterTaskId, MultipartFile file) throws Exception;

    public ObjectContentDto downloadPdfHighlighterFile(Long pdfHighlighterTaskId) throws Exception;

}
