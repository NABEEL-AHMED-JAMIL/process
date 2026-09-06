package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AdHocPromptRequestDto;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * A real, reported leak: a vision agent describing an image volunteered the file's raw MinIO
 * path ("etl-bucket/test-file/52c80c4664...94_big_gallery.png") and closed by inviting the user
 * to "download it in a specific format" -- neither of which makes sense for an image, both
 * sourced from the document-chat prompt's unconditional "Filename: X. Source location: Y." fact
 * and its CSV/PDF/Excel export instructions, applied to every file regardless of type. An image
 * now gets a materially different, shorter prompt (buildImageInstructions) that never hands the
 * model the bucket, path, or even the filename in the first place -- the storage key for this
 * exact file is itself a content-hash-looking name ("52c80c4664...png"), so "don't mention it"
 * alone would not have been enough; the fix is to never hand it over as a fact at all.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatImageInstructionsTest {

    private static final String BUCKET = "etl-bucket";
    private static final String KEY = "test-file/52c80c466436aa335f4464619bad831cfea87cc6d2d1fa19706c3a53e8088951_big_gallery.png";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 1022L;

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
            .thenReturn(Collections.singletonList(new BucketSummaryDto("ETL", BUCKET, "MINIO")));
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, KEY))
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "image/png", false));
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("This appears to be an X-ray image of a person's leg and foot, with a "
                + "visible fracture in one of the bones near the heel.");
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("llava:7b");
        config.setTargetFileTypes("png,jpg");
        config.setInstructions("You are a vision assistant for images stored in a MinIO bucket. "
            + "Look at the image the user opens and answer their question about it.");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
        lenient().when(this.aiAgentService.processAdHoc(any()))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Replied.", "the answer"));
    }

    private FileChatMessageRequestDto request(String message) {
        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setBucket(BUCKET);
        dto.setKey(KEY);
        dto.setAiAgentId(AGENT_ID);
        dto.setMessage(message);
        return dto;
    }

    @Test
    void imageInstructionsNeverMentionTheBucketOrPath() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's in this image?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("neither the bucket nor the storage key/path may appear anywhere in the prompt")
            .doesNotContain(BUCKET)
            .doesNotContain(KEY)
            .doesNotContain("52c80c4664");
    }

    @Test
    void imageInstructionsNeverMentionExportOrDownloadFormats() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's in this image?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("the actual export MACHINERY (the fence+TARGET_FORMAT convention) must be absent "
                + "-- mentioning that Excel/Word/etc. simply aren't available is fine and expected")
            .doesNotContain("TARGET_FORMAT")
            .doesNotContain("```csv");
    }

    @Test
    void imageInstructionsStillCarryTheImageDescriptionAndTheAgentsOwnInstructions() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's in this image?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("the agent's own configured persona and the actual image description must both "
                + "still reach the model -- this fix removes specific leaks, not the whole feature")
            .contains("vision assistant for images stored in a MinIO bucket")
            .contains("fracture in one of the bones near the heel");
    }

    @Test
    void documentInstructionsAreUnaffectedAndStillCarryTheSourceLocation() throws Exception {
        String docKey = "report.txt";
        // A fresh agent, not the vision one from setUp() -- that one is restricted to png/jpg,
        // and a mismatch there would fail for an unrelated reason (see FileChatTargetFileTypeTest).
        Long docAgentId = 1020L;
        AiAgentRuntimeConfigDto docConfig = new AiAgentRuntimeConfigDto();
        docConfig.setProvider("Ollama");
        docConfig.setModel("qwen3:8b");
        docConfig.setTargetFileTypes("txt,csv,pdf");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(docAgentId))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", docConfig));
        lenient().when(this.storageBrowserService.getObjectMetadataCached(BUCKET, docKey))
            .thenReturn(new ObjectMetadataDto(docKey, docKey, 1000L, "now", ETAG, "text/plain", false));
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, docKey, ETAG))
            .thenReturn("Quarterly revenue increased by twelve percent.");

        FileChatMessageRequestDto dto = new FileChatMessageRequestDto();
        dto.setAiAgentId(docAgentId);
        dto.setBucket(BUCKET);
        dto.setKey(docKey);
        dto.setMessage("Where is this file?");

        ResponseDto response = this.service.sendMessage(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("a plain-text document must keep the existing behaviour -- this fix is scoped to "
                + "images only, not a regression for everything else")
            .contains(BUCKET + "/" + docKey)
            .contains("TARGET_FORMAT");
    }
}
