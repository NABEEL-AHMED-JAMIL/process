package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.BucketSummaryDto;
import process.model.dto.FileChatExportRequestDto;
import process.model.dto.FileChatHistoryItemDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.model.service.FileChatExtractionService;
import process.model.service.FileChatService;
import process.model.service.StorageBrowserService;
import process.util.ContentTypeUtil;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static process.util.ProcessUtil.*;

@Service
public class FileChatServiceImpl implements FileChatService {

    private static final Logger logger = LoggerFactory.getLogger(FileChatServiceImpl.class);

    private static final int MAX_HISTORY_MESSAGES = 8;

    private static final int MAX_PROMPT_FILE_CHARS = 30000;

    private static final int MAX_EXPORT_CONTENT_CHARS = 200000;

    private static final Set<String> EXPORT_SOURCE_FORMATS = new HashSet<>(Arrays.asList("csv", "txt"));

    private static final Set<String> EXPORT_TARGET_FORMATS = new HashSet<>(Arrays.asList("xlsx", "docx", "pdf"));

    private final StorageBrowserService storageBrowserService;
    private final FileChatExtractionService fileChatExtractionService;
    private final AiAgentService aiAgentService;

    public FileChatServiceImpl(StorageBrowserService storageBrowserService,
        FileChatExtractionService fileChatExtractionService,
        AiAgentService aiAgentService) {
        this.storageBrowserService = storageBrowserService;
        this.fileChatExtractionService = fileChatExtractionService;
        this.aiAgentService = aiAgentService;
    }

    @Override
    public ResponseDto prepareContext(String bucket, String key) throws Exception {
        ResponseDto validationError = this.validateBucketAccess(bucket, key);
        if (validationError != null) {
            return validationError;
        }
        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadata(bucket, key);
        if (isNull(metadata) || isNull(metadata.getEtag())) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        String text = this.fileChatExtractionService.extractText(bucket, key, metadata.getEtag());
        if (isNull(text) || text.trim().isEmpty()) {
            return new ResponseDto(ERROR, this.unsupportedMessage(key));
        }
        return new ResponseDto(SUCCESS, "Ready.");
    }

    @Override
    public ResponseDto sendMessage(FileChatMessageRequestDto dto) throws Exception {
        if (isNull(dto.getBucket()) || dto.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        if (isNull(dto.getKey()) || dto.getKey().trim().isEmpty()) {
            return new ResponseDto(ERROR, "key missing.");
        }
        if (isNull(dto.getModel()) || dto.getModel().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Pick a model first.");
        }
        if (isNull(dto.getMessage()) || dto.getMessage().trim().isEmpty()) {
            return new ResponseDto(ERROR, "message missing.");
        }
        ResponseDto validationError = this.validateBucketAccess(dto.getBucket(), dto.getKey());
        if (validationError != null) {
            return validationError;
        }

        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadataCached(dto.getBucket(), dto.getKey());
        if (isNull(metadata) || isNull(metadata.getEtag())) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        String fileText = this.fileChatExtractionService.extractText(dto.getBucket(), dto.getKey(), metadata.getEtag());
        if (isNull(fileText) || fileText.trim().isEmpty()) {
            return new ResponseDto(ERROR, this.unsupportedMessage(dto.getKey()));
        }

        String instructions = this.buildInstructions(dto.getKey(), fileText, dto.getHistory());

        AdHocPromptRequestDto adHocPromptRequestDto = new AdHocPromptRequestDto();
        adHocPromptRequestDto.setProvider("Ollama");
        adHocPromptRequestDto.setModel(dto.getModel());
        adHocPromptRequestDto.setInstructions(instructions);
        adHocPromptRequestDto.setText(dto.getMessage());

        try {
            ResponseDto response = this.aiAgentService.processAdHoc(adHocPromptRequestDto);
            if (!SUCCESS.equals(response.getStatus())) {
                return response;
            }
            return new ResponseDto(SUCCESS, "Replied.", response.getData());
        } catch (Exception ex) {
            logger.error("File Chat: sendMessage failed for bucket={} key={}", dto.getBucket(), dto.getKey(), ex);
            return new ResponseDto(ERROR, "The AI didn't respond: " + ex.getMessage());
        }
    }

    @Override
    public ResponseDto exportFile(FileChatExportRequestDto dto) throws Exception {
        if (isNull(dto.getContent()) || dto.getContent().trim().isEmpty()) {
            return new ResponseDto(ERROR, "content missing.");
        }
        if (dto.getContent().length() > MAX_EXPORT_CONTENT_CHARS) {
            return new ResponseDto(ERROR, "content too large to export.");
        }
        String sourceFormat = isNull(dto.getSourceFormat()) ? "" : dto.getSourceFormat().trim().toLowerCase();
        String targetFormat = isNull(dto.getTargetFormat()) ? "" : dto.getTargetFormat().trim().toLowerCase();
        if (!EXPORT_SOURCE_FORMATS.contains(sourceFormat)) {
            return new ResponseDto(ERROR, "Unsupported export source format: " + dto.getSourceFormat());
        }
        if (!EXPORT_TARGET_FORMATS.contains(targetFormat)) {
            return new ResponseDto(ERROR, "Unsupported export target format: " + dto.getTargetFormat());
        }
        try {
            byte[] converted = this.fileChatExtractionService.convertContent(
                dto.getContent().getBytes(StandardCharsets.UTF_8), sourceFormat, targetFormat);
            if (converted == null || converted.length == 0) {
                return new ResponseDto(ERROR, "Could not convert this to a ." + targetFormat + " file.");
            }
            return new ResponseDto(SUCCESS, "Converted.", Base64.getEncoder().encodeToString(converted));
        } catch (Exception ex) {
            logger.error("File Chat: exportFile failed ({} -> {})", sourceFormat, targetFormat, ex);
            return new ResponseDto(ERROR, "The export failed: " + ex.getMessage());
        }
    }

    private String buildInstructions(String key, String fileText, List<FileChatHistoryItemDto> history) {
        boolean truncated = fileText.length() > MAX_PROMPT_FILE_CHARS;
        String promptFileText = truncated ? fileText.substring(0, MAX_PROMPT_FILE_CHARS) : fileText;

        StringBuilder instructions = new StringBuilder();
        instructions.append("You are a helpful assistant that only answers questions about one attached file. ")
            .append("Filename: ").append(key).append(".\n\n")
            .append("Ground every answer strictly in the file content below. ")
            .append("If the user asks something unrelated to this file -- general knowledge, another topic, ")
            .append("small talk, or anything the file content doesn't cover -- do NOT answer it from your own ")
            .append("knowledge. Instead, politely decline in a friendly, brief way: apologize, explain you can ")
            .append("only help with questions about this specific file, and invite them to ask something about ")
            .append("its content instead. Never pretend the file covers something it doesn't.\n\n")
            .append("If the user asks for the answer as a downloadable file, an export, or in a specific data ")
            .append("format (for example \"give me this as a CSV\", \"export the table\", \"I want a file with ")
            .append("...\"), respond with a short one-line note and then put the exact file content inside a ")
            .append("single fenced code block tagged with its format -- ```csv for comma-separated data, ```json ")
            .append("for JSON, ```tsv for tab-separated data, ```txt for plain text. Put nothing except the file's ")
            .append("real content inside that fence (no commentary, no markdown formatting) since it is shown to ")
            .append("the user as an actual downloadable file, not just example text.\n\n")
            .append("If the user specifically asks for Excel, Word, or PDF (not just \"a file\" in general), still ")
            .append("use ```csv for the fence if the data is tabular/spreadsheet-shaped (this becomes the Excel ")
            .append("file), or ```txt if it's prose/document-shaped (this becomes the Word or PDF file) -- exactly ")
            .append("as above. Then, immediately after the closing fence, add one line by itself with nothing else ")
            .append("on it: TARGET_FORMAT: xlsx for Excel, TARGET_FORMAT: docx for Word, or TARGET_FORMAT: pdf for ")
            .append("PDF. Never put that line inside the fence, and never add it unless the user asked for one of ")
            .append("these three formats specifically.\n\n")
            .append("--- FILE CONTENT ---\n")
            .append(promptFileText)
            .append(truncated ? "\n[content truncated -- the file continues beyond what's shown here]" : "")
            .append("\n--- END FILE CONTENT ---\n");

        if (history != null && !history.isEmpty()) {
            int startIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
            instructions.append("\nRecent conversation so far:\n");
            for (int i = startIndex; i < history.size(); i++) {
                FileChatHistoryItemDto turn = history.get(i);
                if (turn == null || isNull(turn.getText())) {
                    continue;
                }
                String role = "assistant".equalsIgnoreCase(turn.getRole()) ? "Assistant" : "User";
                instructions.append(role).append(": ").append(turn.getText()).append("\n");
            }
        }
        return instructions.toString();
    }

    private String unsupportedMessage(String key) {
        String extension = ContentTypeUtil.extensionOf(key);
        return isNull(extension) || extension.isEmpty()
            ? "This file type isn't supported for chat yet."
            : String.format("Couldn't get any readable content out of this .%s file.", extension);
    }

    private ResponseDto validateBucketAccess(String bucket, String key) {
        if (isNull(bucket) || bucket.trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        if (isNull(key) || key.trim().isEmpty()) {
            return new ResponseDto(ERROR, "key missing.");
        }
        boolean bucketOwnedByCaller = this.storageBrowserService.listBuckets().stream()
            .map(BucketSummaryDto::getBucket)
            .anyMatch(bucket::equals);
        if (!bucketOwnedByCaller) {
            return new ResponseDto(ERROR, String.format("Unknown bucket: %s.", bucket));
        }
        return null;
    }

}
