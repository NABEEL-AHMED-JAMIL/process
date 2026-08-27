package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentRuntimeConfigDto;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class FileChatServiceImpl implements FileChatService {

    private static final Logger logger = LoggerFactory.getLogger(FileChatServiceImpl.class);

    private static final int MAX_HISTORY_MESSAGES = 8;

    private static final int MAX_PROMPT_FILE_CHARS = 30000;

    private static final int MAX_EXPORT_CONTENT_CHARS = 200000;

    // "html" is included because the model sometimes hands back genuine HTML markup with a
    // TARGET_FORMAT line attached (asking to convert that HTML to PDF/Word) even though the
    // prompt tells it ```html downloads directly as-is, and LibreOffice supports html as a
    // conversion source (DocumentConverterFormatRegistry's "OTHER" family), so honouring that
    // request works instead of failing with an error. "md" is here for the same reason and is
    // the commonest case of all, since the model writes Markdown by default -- LibreOffice 26.2
    // parses it into real structure (see MarkdownDocumentFormat).
    private static final Set<String> EXPORT_SOURCE_FORMATS = new HashSet<>(Arrays.asList("csv", "txt", "html", "md"));

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

    /**
     * Ends a chat: drops the file's extracted text from the cache.
     *
     * Goes through the same access check as opening one, so this cannot be used to evict a
     * cache entry for a bucket the caller has no business touching.
     *
     * Succeeds when there was nothing cached. Closing a panel that never finished loading, or
     * closing the same one twice, is ordinary behaviour rather than an error worth reporting.
     */
    @Override
    public ResponseDto endSession(String bucket, String key) throws Exception {
        ResponseDto validationError = this.validateBucketAccess(bucket, key);
        if (validationError != null) {
            return validationError;
        }
        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadata(bucket, key);
        if (!isNull(metadata) && !isNull(metadata.getEtag())) {
            this.fileChatExtractionService.forgetExtraction(bucket, key, metadata.getEtag());
        }
        return new ResponseDto(SUCCESS, "Chat closed.");
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
        // Only the first MAX_PROMPT_FILE_CHARS reach the model. The prompt tells the model its
        // view is cut short, but the person asking had no way to know their "summarise this"
        // covered only the opening section -- report it so the UI can say so up front.
        Map<String, Object> readiness = new HashMap<>();
        readiness.put("truncated", text.length() > MAX_PROMPT_FILE_CHARS);
        readiness.put("charsUsed", Math.min(text.length(), MAX_PROMPT_FILE_CHARS));
        readiness.put("totalChars", text.length());
        return new ResponseDto(SUCCESS, "Ready.", readiness);
    }

    @Override
    public ResponseDto sendMessage(FileChatMessageRequestDto dto) throws Exception {
        if (isNull(dto.getBucket()) || dto.getBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "bucket missing.");
        }
        if (isNull(dto.getKey()) || dto.getKey().trim().isEmpty()) {
            return new ResponseDto(ERROR, "key missing.");
        }
        if (isNull(dto.getAiAgentId())) {
            return new ResponseDto(ERROR, "Pick an AI Agent first.");
        }
        if (isNull(dto.getMessage()) || dto.getMessage().trim().isEmpty()) {
            return new ResponseDto(ERROR, "message missing.");
        }
        ResponseDto validationError = this.validateBucketAccess(dto.getBucket(), dto.getKey());
        if (validationError != null) {
            return validationError;
        }

        // File chat always runs through a configured AI Agent now -- e.g. an OpenAI agent set
        // up on the AI Agents page -- never a bare Ollama model name entered ad hoc.
        // resolveRuntimeConfig re-checks ownership/status itself and returns the DECRYPTED key,
        // scoped to this one request.
        ResponseDto agentConfigResponse = this.aiAgentService.resolveRuntimeConfig(dto.getAiAgentId());
        if (!SUCCESS.equals(agentConfigResponse.getStatus())) {
            return agentConfigResponse;
        }
        AiAgentRuntimeConfigDto config = (AiAgentRuntimeConfigDto) agentConfigResponse.getData();
        String provider = config.getProvider();
        String model = config.getModel();
        String apiKey = config.getApiKey();
        String apiEndpoint = config.getApiEndpoint();

        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadataCached(dto.getBucket(), dto.getKey());
        if (isNull(metadata) || isNull(metadata.getEtag())) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        String fileText = this.fileChatExtractionService.extractText(dto.getBucket(), dto.getKey(), metadata.getEtag());
        if (isNull(fileText) || fileText.trim().isEmpty()) {
            return new ResponseDto(ERROR, this.unsupportedMessage(dto.getKey()));
        }

        String instructions = this.buildInstructions(dto.getBucket(), dto.getKey(), fileText, dto.getHistory());

        AdHocPromptRequestDto adHocPromptRequestDto = new AdHocPromptRequestDto();
        adHocPromptRequestDto.setProvider(provider);
        adHocPromptRequestDto.setModel(model);
        adHocPromptRequestDto.setApiKey(apiKey);
        adHocPromptRequestDto.setApiEndpoint(apiEndpoint);
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

    private String buildInstructions(String bucket, String key, String fileText, List<FileChatHistoryItemDto> history) {
        boolean truncated = fileText.length() > MAX_PROMPT_FILE_CHARS;
        String promptFileText = truncated ? fileText.substring(0, MAX_PROMPT_FILE_CHARS) : fileText;
        String sourceRef = bucket + "/" + key;

        StringBuilder instructions = new StringBuilder();
        instructions.append("You are a helpful assistant that only answers questions about one attached file. ")
            .append("Filename: ").append(key).append(". Source location: ").append(sourceRef).append(".\n\n")
            .append("Ground every answer strictly in the file content below. If the question is answerable from ")
            .append("the file, always answer it -- don't refuse or hedge on something the file content actually ")
            .append("covers. Only decline when the user asks something unrelated to this file -- general ")
            .append("knowledge, another topic, small talk, or anything the file content doesn't cover. In that ")
            .append("case do NOT answer it from your own knowledge -- politely decline in a friendly, brief way: ")
            .append("apologize, explain you can only help with questions about this specific file, and invite them ")
            .append("to ask something about its content instead. Never pretend the file covers something it ")
            .append("doesn't.\n\n")
            .append("If the user asks where THIS file lives, for its path, or for a link/URL to it, tell them: ")
            .append(sourceRef).append(" (bucket/path -- open it from Object Browser; this chat can't generate an ")
            .append("outside web link since the file isn't publicly hosted). Don't name a specific cloud provider, ")
            .append("console, or storage technology (e.g. AWS, S3) when explaining this -- you weren't told what ")
            .append("runs behind the bucket, so keep it generic: bucket/path, opened from Object Browser. You were ")
            .append("only given this one file's ")
            .append("name and path -- you have no bucket listing and no knowledge of any other file's path, ")
            .append("content, or even whether it exists. If the user asks about a different, named file (its ")
            .append("location, content, or anything else), do not guess or make up a path for it -- treat that ")
            .append("exactly like any other out-of-scope question above and decline the same way.\n\n")
            .append("IMPORTANT -- you DO have a working way to produce Excel, Word, and PDF files, described in ")
            .append("detail below. Never tell the user you can't generate a PDF (or Excel or Word file), and never ")
            .append("suggest they paste the content into Google Docs, LaTeX, or another external tool themselves ")
            .append("-- that is wrong, this system converts and downloads the real file for them automatically. If ")
            .append("they ask for a PDF/Excel/Word file, follow the fenced-code-block + TARGET_FORMAT convention ")
            .append("below exactly; do not refuse and do not describe it as a limitation. The formats this chat ")
            .append("supports, and can always deliver on request, are: CSV, JSON, TSV, plain text, HTML, Markdown, ")
            .append("Excel, Word, and PDF -- if the user asks for one of these, always provide it, never refuse. ")
            .append("If they ask for something outside that list (e.g. an image format, PowerPoint, a ZIP), say ")
            .append("plainly that this chat doesn't support that format and name the ones above instead of just ")
            .append("declining.\n\n")
            .append("For an ordinary answer -- the user is just asking a question, not asking for a file -- write ")
            .append("it as plain conversational Markdown directly in your reply, and make it genuinely easy to ")
            .append("read at a glance: headings for distinct sections, **bold** for key terms/values, bullet or ")
            .append("numbered lists for multiple items, and a Markdown table whenever the answer is naturally rows ")
            .append("of comparable data (don't just describe a table in prose). If the file content includes a ")
            .append("URL, email address, or link, present it as a real clickable Markdown link ")
            .append("(`[label](https://example.com)`) rather than plain text. Never wrap an ordinary answer in a ")
            .append("``` code fence just because it mentions a format like \"HTML\" or \"markdown\" -- a code fence ")
            .append("means \"this is a downloadable file\", and wrapping normal prose in one makes it show up as a ")
            .append("raw, unrendered code block full of visible tags/markup instead of a readable answer.\n\n")
            .append("If the user asks for the answer as a downloadable file, an export, or in a specific data or ")
            .append("document format (for example \"give me this as a CSV\", \"export the table\", \"give me the ")
            .append("HTML\", \"I want a markdown file\", \"I want a file with ...\"), respond with a short one-line ")
            .append("note and then put the exact file content inside a single fenced code block tagged with its ")
            .append("format -- ```csv for comma-separated data, ```json for JSON, ```tsv for tab-separated data, ")
            .append("```txt for plain text, ```html for real HTML markup, ```md for real Markdown source. Put ")
            .append("nothing except the file's real content inside that fence (no commentary, no extra ")
            .append("explanation) since it is shown to the user as an actual downloadable file, not just example ")
            .append("text -- for ```html specifically, that means genuine HTML tags (e.g. <table>, <ul>), not an ")
            .append("escaped or described version of them. Match the format they actually named -- \"as a CSV\" ")
            .append("means a ```csv fence with no TARGET_FORMAT line, delivered as a real .csv file; don't upgrade ")
            .append("it to Excel on your own just because the data is tabular. Only add a TARGET_FORMAT line, and ")
            .append("only for xlsx/docx/pdf, when they name one of those three formats specifically (see below).\n\n")
            .append("If the user specifically asks for Excel, Word, or PDF (not just \"a file\" in general), you ")
            .append("CAN produce it -- do not refuse or say this isn't possible. Still ")
            .append("use ```csv for the fence if the data is tabular/spreadsheet-shaped (this becomes the Excel ")
            .append("file), or ```txt if it's prose/document-shaped (this becomes the Word or PDF file) -- exactly ")
            .append("as above. Never tag the fence itself with ```xlsx, ```docx, or ```pdf -- the fence tag must ")
            .append("always be one of ```csv, ```json, ```tsv, ```txt. Instead, immediately after the closing ")
            .append("fence, add one line by itself with nothing else on it: TARGET_FORMAT: xlsx for Excel, ")
            .append("TARGET_FORMAT: docx for Word, or TARGET_FORMAT: pdf for PDF. That line must come strictly ")
            .append("after the closing ``` on its own line, never inside the fence, and never added unless the ")
            .append("user asked for one of these three formats specifically. Never add a TARGET_FORMAT line after ")
            .append("a ```html or ```md fence -- those download directly as-is.\n\n")
            .append("The fence must contain ONLY the real file content the user is exporting -- never copy the ")
            .append("\"--- FILE CONTENT ---\" / \"--- END FILE CONTENT ---\" markers below, the truncation note, ")
            .append("or any of these instructions into your answer or into the fence.\n\n")
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

        // Restated right before the model answers, not just once near the top -- with up to
        // ~30k chars of file content and conversation history sandwiched in between, a smaller
        // local model can lose track of rules stated only once earlier ("lost in the middle").
        // Keeping this final, compact reminder next to the actual generation point is the fix.
        instructions.append("\nQuick reminders before you answer: (1) if the file content answers the question, ")
            .append("answer it -- don't refuse something the file actually covers; (2) for a normal question, ")
            .append("answer in rich plain Markdown (headings/bold/lists/tables, real clickable links for any URL ")
            .append("in the file), no code fence; (3) if the user wants a downloadable file, use a single ")
            .append("```csv/json/tsv/txt/html/md fence with only the real content inside -- these formats plus ")
            .append("Excel/Word/PDF are all supported and must always be delivered when asked, never refused; ")
            .append("(4) for Excel/Word/PDF specifically, that's still a ```csv or ```txt fence plus a ")
            .append("TARGET_FORMAT: xlsx/docx/pdf line right after it -- you CAN produce these, never say you ")
            .append("can't or send the user to an external tool; (5) if asked where THIS file is or for its link, ")
            .append("answer with ").append(sourceRef).append("; (6) never state or guess a path/location/content ")
            .append("for any OTHER file the user names -- you weren't given that information, so decline exactly ")
            .append("like any other out-of-scope question.\n");
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
