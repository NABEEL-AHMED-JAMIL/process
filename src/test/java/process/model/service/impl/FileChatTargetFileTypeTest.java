package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.EmbeddingService;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.util.OpenSearchRagClient;
import process.util.ProcessUtil;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The actual enforcement behind an agent's "Target file types" field. Before this, the field
 * was required at save time, stored, and shown in the AI Agents screen -- and consulted nowhere:
 * an agent configured for "csv,json" could be picked and used against a PDF with no error at
 * all. Both {@code prepareContext} (checked before any storage work, so a mismatch is caught
 * before the panel even opens) and {@code sendMessage} (the actual API contract, checked
 * independently since nothing forces a caller through prepareContext first) now reject a
 * mismatched file/agent pairing.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatTargetFileTypeTest {

    private static final String BUCKET = "docs";
    private static final Long AGENT_ID = 2000L;

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private FileChatExtractionService fileChatExtractionService;
    @Mock private AiAgentService aiAgentService;
    @Mock private OpenSearchRagClient openSearchRagClient;
    @Mock private EmbeddingService embeddingService;

    private FileChatServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new FileChatServiceImpl(this.storageBrowserService,
            this.fileChatExtractionService, this.aiAgentService,
            this.openSearchRagClient, this.embeddingService);

        lenient().when(this.storageBrowserService.listBuckets())
            .thenReturn(Collections.singletonList(new BucketSummaryDto("Docs", BUCKET, "MINIO")));
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);
        lenient().when(this.aiAgentService.processAdHoc(any()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Replied.", "the answer"));
    }

    private void stubAgent(String targetFileTypes) throws Exception {
        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("qwen3:8b");
        config.setTargetFileTypes(targetFileTypes);
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
    }

    private void stubFile(String key) throws Exception {
        lenient().when(this.storageBrowserService.getObjectMetadata(BUCKET, key))
            .thenReturn(new ObjectMetadataDto(key, key, 1000L, "now", "etag-1", "text/plain", false));
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, key))
            .thenReturn(new ObjectMetadataDto(key, key, 1000L, "now", "etag-1", "text/plain", false));
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, key, "etag-1"))
            .thenReturn("Some extracted file text.");
    }

    private FileChatMessageRequestDto request(String key) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(BUCKET);
        dto.setKey(key);
        dto.setAiAgentId(AGENT_ID);
        dto.setMessage("What does this say?");
        return dto;
    }

    // ---- sendMessage --------------------------------------------------------------------------

    @Test
    void sendMessageRejectsAFileTypeTheAgentDoesNotHandle() throws Exception {
        this.stubAgent("csv,json");
        this.stubFile("report.pdf");

        ResponseDto response = this.service.sendMessage(this.request("report.pdf"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage())
            .as("must name the agent's actual accepted types and the file, not a generic refusal")
            .contains("csv,json")
            .contains("report.pdf");
        verify(this.fileChatExtractionService, never()).extractText(anyString(), anyString(), anyString());
        verify(this.aiAgentService, never()).processAdHoc(any());
    }

    @Test
    void sendMessageAllowsAMatchingFileType() throws Exception {
        this.stubAgent("csv,pdf,txt");
        this.stubFile("report.pdf");

        ResponseDto response = this.service.sendMessage(this.request("report.pdf"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void matchingIgnoresCaseAndWhitespace() throws Exception {
        this.stubAgent(" PDF , Csv ");
        this.stubFile("REPORT.PDF");

        ResponseDto response = this.service.sendMessage(this.request("REPORT.PDF"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void blankTargetFileTypesIsUnrestricted() throws Exception {
        // Required at agent-save time, so blank is only reachable for an agent saved before that
        // validation existed -- treated as "accepts anything" so it can't silently break one.
        this.stubAgent("");
        this.stubFile("anything.xyz");

        ResponseDto response = this.service.sendMessage(this.request("anything.xyz"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void aFileWithNoExtensionIsRejectedByARestrictedAgent() throws Exception {
        this.stubAgent("csv,pdf");
        this.stubFile("README");

        ResponseDto response = this.service.sendMessage(this.request("README"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
    }

    @Test
    void gzipWrappedFilesMatchOnTheInnerExtension() throws Exception {
        this.stubAgent("csv");
        this.stubFile("audit.csv.gz");

        ResponseDto response = this.service.sendMessage(this.request("audit.csv.gz"));

        assertThat(response.getStatus())
            .as("what matters is what the file becomes once unwrapped, matching how "
                + "ContentTypeUtil.innerExtensionOfGzip is used elsewhere for the same reason")
            .isEqualTo(ProcessUtil.SUCCESS);
    }

    // ---- prepareContext -------------------------------------------------------------------------

    @Test
    void prepareContextRejectsAMismatchBeforeTouchingStorageAtAll() throws Exception {
        this.stubAgent("csv,json");
        this.stubFile("report.pdf");

        ResponseDto response = this.service.prepareContext(BUCKET, "report.pdf", AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("csv,json").contains("report.pdf");
        verify(this.storageBrowserService, never()).getObjectMetadata(anyString(), anyString());
        verify(this.fileChatExtractionService, never()).extractText(anyString(), anyString(), anyString());
    }

    @Test
    void prepareContextSucceedsForAMatchingFileType() throws Exception {
        this.stubAgent("pdf");
        this.stubFile("report.pdf");

        ResponseDto response = this.service.prepareContext(BUCKET, "report.pdf", AGENT_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void prepareContextWithNoAgentChosenYetSkipsTheCheck() throws Exception {
        this.stubFile("report.pdf");

        ResponseDto response = this.service.prepareContext(BUCKET, "report.pdf", null);

        assertThat(response.getStatus())
            .as("no agent picked yet is not the same as a mismatch -- readiness must still work")
            .isEqualTo(ProcessUtil.SUCCESS);
    }
}
