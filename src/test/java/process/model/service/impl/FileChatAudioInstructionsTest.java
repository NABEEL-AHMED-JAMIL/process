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
    // Only emailExport reaches it; these cases never do. Present so the constructor resolves.
    @Mock private process.model.service.FileShareService fileShareService;

    private FileChatServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new FileChatServiceImpl(this.storageBrowserService,
            this.fileChatExtractionService, this.aiAgentService,
            this.openSearchRagClient, this.embeddingService, this.fileShareService);

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

    /**
     * A two-hour recording transcribes to far more than a local Ollama agent's 24,000-character
     * budget, so resolveContext hands the audio prompt a transcript that stops partway through.
     * The audio prompt used to append that content between its transcript markers with nothing to
     * say so, while also instructing the model to ground every answer strictly in the transcript
     * and never to hedge on something it covers. Asked whether a date for the migration was agreed
     * -- said in the last twenty minutes -- the model reported that the recording does not cover
     * it, in the same confident voice it uses for the opening minutes, as though that were a fact
     * about the meeting rather than about where the transcript was cut.
     */
    @Test
    void aTruncatedTranscriptSaysSoRatherThanReadingAsTheWholeRecording() throws Exception {
        lenient().when(this.fileChatExtractionService.extractText(BUCKET, KEY, ETAG))
            .thenReturn(longTranscript());

        ResponseDto response = this.service.sendMessage(
            this.request("Did they agree a date for the migration?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions())
            .as("the audio prompt must carry the same truncation note the document prompt does -- "
                + "the model is told to answer anything the transcript covers, so it has to be "
                + "told the transcript stops early")
            .contains("--- AUDIO TRANSCRIPT ---")
            .contains("[content truncated");
    }

    /** The converse, so the note is not simply stapled on unconditionally. */
    @Test
    void aTranscriptThatFitsCarriesNoTruncationNote() throws Exception {
        ResponseDto response = this.service.sendMessage(this.request("What's this voicemail about?"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<AdHocPromptRequestDto> captor = ArgumentCaptor.forClass(AdHocPromptRequestDto.class);
        verify(this.aiAgentService).processAdHoc(captor.capture());
        assertThat(captor.getValue().getInstructions()).doesNotContain("[content truncated");
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

    /** Comfortably past Ollama's 24,000-character budget, so resolveContext really does cut it. */
    private static String longTranscript() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 30000) {
            sb.append("And then somebody said something else worth transcribing at length. ");
        }
        return sb.toString();
    }
}
