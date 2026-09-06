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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * The same leak fixed for images (FileChatImageInstructionsTest) generalizes to audio: a
 * transcript is a model-generated rendering of the recording, not literal document text, so it
 * must never be handed the document prompt's unconditional "Filename: X. Source location: Y."
 * fact or its CSV/Excel/PDF export instructions -- neither makes sense for a voicemail or meeting
 * recording. Before this fix, mp3/m4a fell through {@code ContentTypeUtil.isImage(key) == false}
 * straight into the document prompt, inheriting the identical problem.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class FileChatAudioInstructionsTest {

    private static final String BUCKET = "etl-bucket";
    private static final String KEY = "voicemail-2024-03-12.mp3";
    private static final String ETAG = "etag-123";
    private static final Long AGENT_ID = 1023L;

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
            .thenReturn(new ObjectMetadataDto(KEY, KEY, 1000L, "now", ETAG, "audio/mpeg", false));
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn("Hi, this is a message about rescheduling tomorrow's meeting to 3pm.");
        lenient().when(this.openSearchRagClient.isEnabled()).thenReturn(false);

        AiAgentRuntimeConfigDto config = new AiAgentRuntimeConfigDto();
        config.setProvider("Ollama");
        config.setModel("qwen3:8b");
        config.setInstructions("You transcribe and summarize voicemails for the support team.");
        lenient().when(this.aiAgentService.resolveRuntimeConfig(AGENT_ID))
            .thenReturn(new ResponseDto(ProcessUtil.SUCCESS, "Resolved.", config));
        lenient().when(this.aiAgentService.processAdHoc(org.mockito.ArgumentMatchers.any()))
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
    void audioInstructionsNeverMentionTheBucketOrPath() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's this voicemail about?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("neither the bucket nor the storage key/path may appear anywhere in the prompt")
            .doesNotContain(BUCKET)
            .doesNotContain(KEY);
    }

    @Test
    void audioInstructionsNeverMentionExportOrDownloadFormats() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's this voicemail about?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .doesNotContain("TARGET_FORMAT")
            .doesNotContain("```csv");
    }

    @Test
    void audioInstructionsStillCarryTheTranscriptAndTheAgentsOwnInstructions() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's this voicemail about?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .contains("transcribe and summarize voicemails")
            .contains("rescheduling tomorrow's meeting to 3pm");
    }
}
