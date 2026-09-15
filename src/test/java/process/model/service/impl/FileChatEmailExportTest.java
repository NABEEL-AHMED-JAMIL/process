package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import process.model.dto.FileChatExportRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.model.service.FileChatExtractionService;
import process.model.service.FileShareService;
import process.model.service.StorageBrowserService;
import process.util.OpenSearchRagClient;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Emailing a chat export, and the rules it must not be allowed to invent its own version of.
 *
 * A conversation's export is not an object in a bucket -- it is a conversion of a reply on screen
 * -- so FileShareService.emailFile's bucket/key path cannot carry it. The new path therefore had a
 * choice: restate "a valid address, at most 20 MiB" here, or route through the service that
 * already owns those rules. It routes. These tests exist mostly to keep it that way: the second
 * copy of a limit is the one that drifts, and a drifted size ceiling is how a 25 MB attachment
 * reaches a transport that refuses it and returns an untyped error string.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class FileChatEmailExportTest {

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private FileChatExtractionService fileChatExtractionService;
    @Mock private AiAgentService aiAgentService;
    @Mock private OpenSearchRagClient openSearchRagClient;
    @Mock private EmbeddingService embeddingService;
    @Mock private FileShareService fileShareService;

    private FileChatServiceImpl service;

    private static final byte[] PDF_BYTES = new byte[] { 0x25, 0x50, 0x44, 0x46, 0x2D };

    @BeforeEach
    void setUp() {
        this.service = new FileChatServiceImpl(this.storageBrowserService,
            this.fileChatExtractionService, this.aiAgentService,
            this.openSearchRagClient, this.embeddingService, this.fileShareService);
    }

    private FileChatExportRequestDto request(String content, String source, String target, String recipient) {
        FileChatExportRequestDto dto = new FileChatExportRequestDto();
        dto.setContent(content);
        dto.setSourceFormat(source);
        dto.setTargetFormat(target);
        dto.setRecipientEmail(recipient);
        return dto;
    }

    private void converterProduces(byte[] bytes) throws Exception {
        when(this.fileChatExtractionService.convertContent(any(byte[].class), anyString(), anyString()))
            .thenReturn(bytes);
    }

    private void shareServiceAccepts() throws Exception {
        when(this.fileShareService.emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), any())).thenReturn(new ResponseDto(SUCCESS, "Email sent."));
    }

    // --- 20-25. The recipient, checked before any work is spent -------------------------------

    @Test
    @DisplayName("20. a missing recipient is refused")
    void missingRecipientIsRefused() throws Exception {
        ResponseDto response = this.service.emailExport(request("# Report", "md", "pdf", null));
        assertEquals(ERROR, response.getStatus());
    }

    @Test
    @DisplayName("21. a blank recipient is refused")
    void blankRecipientIsRefused() throws Exception {
        assertEquals(ERROR, this.service.emailExport(request("# Report", "md", "pdf", "   ")).getStatus());
    }

    @Test
    @DisplayName("22. the recipient is checked BEFORE the converter is invoked")
    void recipientIsCheckedBeforeConverting() throws Exception {
        this.service.emailExport(request("# Report", "md", "pdf", null));
        // LibreOffice is one shared process on this deployment. Spending it on a request that was
        // going to be refused anyway is work taken from every conversion queued behind it.
        verify(this.fileChatExtractionService, never())
            .convertContent(any(byte[].class), anyString(), anyString());
    }

    @Test
    @DisplayName("23. the address rule is NOT restated here -- it is the share service's to enforce")
    void addressValidationIsDelegated() throws Exception {
        converterProduces(PDF_BYTES);
        when(this.fileShareService.emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), any()))
            .thenReturn(new ResponseDto(ERROR, "Enter a valid recipient email address."));
        ResponseDto response = this.service.emailExport(request("# Report", "md", "pdf", "not-an-address"));
        assertEquals(ERROR, response.getStatus());
        // The point: a malformed address that gets past the blank check reaches the one component
        // that owns the pattern, rather than a second regex here that could disagree with it.
        verify(this.fileShareService).emailGeneratedFile(eq("not-an-address"), anyString(), anyString(),
            anyString(), any(byte[].class), any());
    }

    @Test
    @DisplayName("24. the size ceiling is the share service's too, not a copy")
    void sizeCeilingIsDelegated() throws Exception {
        converterProduces(new byte[4096]);
        when(this.fileShareService.emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), any()))
            .thenReturn(new ResponseDto(ERROR, "This export is 21.0 MB -- too large to email (limit is 20.0 MB)."));
        ResponseDto response = this.service.emailExport(request("# Report", "md", "pdf", "a@b.com"));
        assertEquals(ERROR, response.getStatus());
        assertTrue(response.getMessage().contains("too large"));
    }

    @Test
    @DisplayName("25. a successful send reports success")
    void successfulSend() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        assertEquals(SUCCESS, this.service.emailExport(request("# Report", "md", "pdf", "a@b.com")).getStatus());
    }

    // --- 26-33. The same validation as the download path, because they share one method --------

    @Test
    @DisplayName("26. empty content is refused")
    void emptyContentIsRefused() throws Exception {
        assertEquals(ERROR, this.service.emailExport(request("   ", "md", "pdf", "a@b.com")).getStatus());
    }

    @Test
    @DisplayName("27. an unsupported source format is refused")
    void unsupportedSourceIsRefused() throws Exception {
        assertEquals(ERROR, this.service.emailExport(request("x", "docx", "pdf", "a@b.com")).getStatus());
    }

    @Test
    @DisplayName("28. an unsupported target format is refused")
    void unsupportedTargetIsRefused() throws Exception {
        assertEquals(ERROR, this.service.emailExport(request("x", "md", "png", "a@b.com")).getStatus());
    }

    @Test
    @DisplayName("29. content beyond the export ceiling is refused")
    void oversizeContentIsRefused() throws Exception {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 200001; i++) {
            huge.append('x');
        }
        assertEquals(ERROR, this.service.emailExport(request(huge.toString(), "md", "pdf", "a@b.com")).getStatus());
    }

    @Test
    @DisplayName("30. the download path refuses exactly the same inputs")
    void downloadPathAgreesWithEmailPath() throws Exception {
        // One method validates for both, so the two can never drift into disagreeing about which
        // formats are allowed -- which is the failure a flag on exportFile would have risked.
        assertEquals(ERROR, this.service.exportFile(request("x", "docx", "pdf", null)).getStatus());
        assertEquals(ERROR, this.service.exportFile(request("x", "md", "png", null)).getStatus());
        assertEquals(ERROR, this.service.exportFile(request("  ", "md", "pdf", null)).getStatus());
    }

    @Test
    @DisplayName("31. a converter that produces nothing is an error, not an empty attachment")
    void emptyConversionIsRefused() throws Exception {
        converterProduces(new byte[0]);
        assertEquals(ERROR, this.service.emailExport(request("# R", "md", "pdf", "a@b.com")).getStatus());
        verify(this.fileShareService, never()).emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), any());
    }

    @Test
    @DisplayName("32. a converter that throws is reported, not propagated as a 500")
    void converterFailureIsReported() throws Exception {
        when(this.fileChatExtractionService.convertContent(any(byte[].class), anyString(), anyString()))
            .thenThrow(new IllegalStateException("soffice timed out"));
        ResponseDto response = this.service.emailExport(request("# R", "md", "pdf", "a@b.com"));
        assertEquals(ERROR, response.getStatus());
        assertTrue(response.getMessage().contains("soffice timed out"), response.getMessage());
    }

    @Test
    @DisplayName("33. the content is converted from UTF-8 bytes, so a curly quote is not mangled twice")
    void contentIsConvertedAsUtf8() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("“smart quotes” and — dashes", "md", "pdf", "a@b.com"));
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(this.fileChatExtractionService).convertContent(bytes.capture(), eq("md"), eq("pdf"));
        assertTrue(new String(bytes.getValue(), StandardCharsets.UTF_8).contains("“smart quotes”"),
            "the RAG index already lost these once to a charset default; the export must not repeat it");
    }

    // --- 34-39. What the recipient actually receives -------------------------------------------

    @Test
    @DisplayName("34. a PDF export is labelled application/pdf, not octet-stream")
    void pdfContentType() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("# R", "md", "pdf", "a@b.com"));
        verify(this.fileShareService).emailGeneratedFile(eq("a@b.com"), anyString(), anyString(),
            eq("application/pdf"), any(byte[].class), any());
    }

    @Test
    @DisplayName("35. a docx export carries the Word content type")
    void docxContentType() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("# R", "md", "docx", "a@b.com"));
        verify(this.fileShareService).emailGeneratedFile(anyString(), anyString(), anyString(),
            eq("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            any(byte[].class), any());
    }

    @Test
    @DisplayName("36. an xlsx export carries the spreadsheet content type")
    void xlsxContentType() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("a,b\n1,2", "csv", "xlsx", "a@b.com"));
        verify(this.fileShareService).emailGeneratedFile(anyString(), anyString(), anyString(),
            eq("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            any(byte[].class), any());
    }

    @Test
    @DisplayName("37. the attachment is named for the format the reader chose")
    void filenameMatchesFormat() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("# R", "md", "pdf", "a@b.com"));
        verify(this.fileShareService).emailGeneratedFile(anyString(), eq("chat-export.pdf"),
            eq("chat-export.pdf"), anyString(), any(byte[].class), any());
    }

    @Test
    @DisplayName("38. the converted bytes are what is sent, not the source text")
    void convertedBytesAreSent() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        this.service.emailExport(request("# R", "md", "pdf", "a@b.com"));
        ArgumentCaptor<byte[]> sent = ArgumentCaptor.forClass(byte[].class);
        verify(this.fileShareService).emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), sent.capture(), any());
        assertEquals(PDF_BYTES.length, sent.getValue().length);
    }

    @Test
    @DisplayName("39. the sender's note rides along when one was typed")
    void senderNoteIsCarried() throws Exception {
        converterProduces(PDF_BYTES);
        shareServiceAccepts();
        FileChatExportRequestDto dto = request("# R", "md", "pdf", "a@b.com");
        dto.setMessage("Here is the summary we discussed.");
        this.service.emailExport(dto);
        verify(this.fileShareService).emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), eq("Here is the summary we discussed."));
    }

    @Test
    @DisplayName("40. the download path still returns base64 and never emails anything")
    void downloadPathDoesNotEmail() throws Exception {
        converterProduces(PDF_BYTES);
        ResponseDto response = this.service.exportFile(request("# R", "md", "pdf", null));
        assertEquals(SUCCESS, response.getStatus());
        assertNotNull(response.getData());
        verify(this.fileShareService, never()).emailGeneratedFile(anyString(), anyString(), anyString(),
            anyString(), any(byte[].class), any());
    }
}
