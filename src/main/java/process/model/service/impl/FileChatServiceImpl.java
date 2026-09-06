package process.model.service.impl;

import org.slf4j.Logger;
import java.util.Collections;
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
import process.model.service.EmbeddingService;
import process.model.service.FileChatExtractionService;
import process.model.service.FileChatService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.ContentTypeUtil;
import process.util.OpenSearchRagClient;
import process.util.TextChunker;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class FileChatServiceImpl implements FileChatService {

    private static final Logger logger = LoggerFactory.getLogger(FileChatServiceImpl.class);

    private static final int MAX_HISTORY_MESSAGES = 8;

    /**
     * How much of a file reaches the model, by provider.
     *
     * One global 30,000 was sized for 2022 context windows -- roughly 7,500 tokens, when the
     * hosted models now take 128k to 200k. On this platform's own files that cut one in six of
     * them off mid-document: average 12,299 characters, largest 61,866.
     *
     * The number is wrong in both directions from a single value, which is why it is per
     * provider: a local Ollama is often built with an 8k window and would choke on what Claude
     * reads comfortably. Roughly four characters to a token, then a wide margin for the
     * instructions and the conversation that share the window.
     */
    private static final Map<String, Integer> PROMPT_FILE_CHARS_BY_PROVIDER;
    static {
        Map<String, Integer> limits = new HashMap<>();
        limits.put("ANTHROPIC", 400000);
        limits.put("OPENAI", 250000);
        // Keyed on the AI_PROVIDER lookup value with punctuation stripped (see providerKey
        // below), not the literal lookup spelling -- "AzureOpenAI" upper-cases to "AZUREOPENAI",
        // which never matched a key spelt "AZURE-OPENAI" here, so every Azure OpenAI agent
        // silently fell through to the 24k default meant for a local model with a small context
        // window, needlessly truncating a provider that can actually take 250k.
        limits.put("AZUREOPENAI", 250000);
        limits.put("OLLAMA", 24000);
        PROMPT_FILE_CHARS_BY_PROVIDER = Collections.unmodifiableMap(limits);
    }

    private static String providerKey(String provider) {
        return provider == null ? "" : provider.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** Used when the provider is unknown, and deliberately the smallest of them. */
    private static final int DEFAULT_PROMPT_FILE_CHARS = 24000;

    /**
     * The chosen agent's runtime config, or null when there is none to ask -- shared by
     * {@code prepareContext}'s limit lookup and its target-file-type check, so readiness
     * resolves the agent once per request, not twice.
     *
     * Never throws: a readiness check failing because an agent lookup did would stop the panel
     * opening at all.
     */
    private AiAgentRuntimeConfigDto agentConfigFor(Long aiAgentId) {
        if (isNull(aiAgentId)) {
            return null;
        }
        try {
            ResponseDto config = this.aiAgentService.resolveRuntimeConfig(aiAgentId);
            if (config != null && SUCCESS.equals(config.getStatus()) && config.getData() != null) {
                return (AiAgentRuntimeConfigDto) config.getData();
            }
        } catch (Exception ex) {
            logger.warn("Could not resolve agent {}: {}", aiAgentId, ex.getMessage());
        }
        return null;
    }

    /**
     * Whether an agent configured for these target file types can be used to chat about this
     * key -- the actual enforcement behind the "Target file types" field, which used to be
     * collected, stored and displayed everywhere and consulted nowhere: nothing stopped an
     * agent configured for "csv,json" being picked, and used without error, against a PDF.
     *
     * Blank/unset target types means unrestricted -- the field is required at agent-save time
     * (see AiAgentServiceImpl.saveAiAgent), so blank is only reachable for an agent saved before
     * that validation existed; treating it as "accepts anything" rather than "accepts nothing"
     * is the direction that can't silently break an agent nobody touched.
     */
    private static boolean acceptsFileType(String targetFileTypes, String key) {
        if (isNull(targetFileTypes) || targetFileTypes.trim().isEmpty()) {
            return true;
        }
        String extension = ContentTypeUtil.isGzip(key)
            ? ContentTypeUtil.innerExtensionOfGzip(key) : ContentTypeUtil.extensionOf(key);
        if (extension.isEmpty()) {
            return false;
        }
        for (String type : targetFileTypes.split(",")) {
            if (type.trim().equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    private static String fileTypeMismatchMessage(String targetFileTypes, String key) {
        return String.format("This agent only handles %s files -- pick a different agent for %s.",
            targetFileTypes, key);
    }

    private static int promptFileCharsFor(String provider) {
        String key = providerKey(provider);
        Integer limit = PROMPT_FILE_CHARS_BY_PROVIDER.get(key);
        if (limit != null) {
            return limit;
        }
        if (!key.isEmpty()) {
            // key empty means no agent chosen yet -- routine, not worth a log. A non-empty key
            // that still misses means a real provider (from the tenant-extendable AI_PROVIDER
            // lookup, addable through Settings with no code deploy) has no budget entry here and
            // is silently getting the smallest default -- this is the one place that surfaces it,
            // since nothing at agent-save time or startup cross-checks the lookup against this map.
            logger.warn("No configured prompt-file-char budget for provider '{}'; using the "
                + "default of {} characters.", provider, DEFAULT_PROMPT_FILE_CHARS);
        }
        return DEFAULT_PROMPT_FILE_CHARS;
    }

    /**
     * How many chunks of retrieved context to hand the model for one question.
     *
     * At ~1000 chars per chunk (TextChunker.DEFAULT_CHUNK_SIZE), 8 chunks is up to ~8000 chars
     * of the most relevant material -- comfortably inside even Ollama's smallest budget above,
     * so retrieval never has to be truncated a second time on top of being selected.
     */
    private static final int RAG_TOP_K = 8;

    /**
     * One monitor per bucket+key+etag ever indexed, so two concurrent requests for the SAME
     * not-yet-indexed file (two tabs, a double-submit) serialize on the index step instead of
     * racing: both would otherwise see "not indexed" from an unsynchronized check, both chunk
     * and embed the file, and their {@code indexChunks} calls (each a delete-then-write) could
     * interleave -- one request's delete removing the other's just-written docs. Entries are
     * never evicted; the cost is one small Object per distinct file version this process has
     * ever indexed, for the life of the process -- a deliberate trade against the complexity of
     * reference-counted cleanup for something this infrequent (once per file version, not once
     * per message).
     */
    private final ConcurrentHashMap<String, Object> indexLocks = new ConcurrentHashMap<>();

    private Object indexLockFor(String bucket, String key, String etag) {
        return this.indexLocks.computeIfAbsent(bucket + "|" + key + "|" + etag, ignored -> new Object());
    }

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
    private final OpenSearchRagClient openSearchRagClient;
    private final EmbeddingService embeddingService;

    public FileChatServiceImpl(StorageBrowserService storageBrowserService,
        FileChatExtractionService fileChatExtractionService,
        AiAgentService aiAgentService,
        OpenSearchRagClient openSearchRagClient,
        EmbeddingService embeddingService) {
        this.storageBrowserService = storageBrowserService;
        this.fileChatExtractionService = fileChatExtractionService;
        this.aiAgentService = aiAgentService;
        this.openSearchRagClient = openSearchRagClient;
        this.embeddingService = embeddingService;
    }

    /**
     * Whether RAG can actually run right now, rather than degrading to whole-file truncation.
     *
     * Two independent things have to be true: OpenSearch is configured (opensearch.url set --
     * see OpenSearchRagClient.isEnabled), and an embedding model is genuinely reachable, not
     * merely configured (see EmbeddingServiceImpl.isAvailable, which makes a real call). Either
     * one being false must degrade file chat, never break it -- a tenant asking about a file in
     * an environment with no OpenSearch should still get an answer from the raw extracted text
     * (truncated if it does not fit), the same experience this feature always had, not an error.
     */
    private boolean ragAvailable() {
        return this.openSearchRagClient.isEnabled() && this.embeddingService.isAvailable();
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
    public ResponseDto prepareContext(String bucket, String key, Long aiAgentId) throws Exception {
        ResponseDto validationError = this.validateBucketAccess(bucket, key);
        if (validationError != null) {
            return validationError;
        }
        AiAgentRuntimeConfigDto agentConfig = this.agentConfigFor(aiAgentId);
        // Checked before touching storage at all -- an agent that can't handle this file type
        // never needed its text extracted in the first place, and the panel should say so
        // immediately rather than only once the user has already typed a question.
        if (agentConfig != null && !acceptsFileType(agentConfig.getTargetFileTypes(), key)) {
            return new ResponseDto(ERROR, fileTypeMismatchMessage(agentConfig.getTargetFileTypes(), key));
        }
        // Cached, not a live storage round trip: the legacy UI now calls this once per message
        // (refreshChatReadiness), same as sendMessage below it, which already reads the cached
        // value -- an object's etag does not change between one message and the next in the
        // same conversation, so there is nothing this needs that the cache would not already have.
        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadataCached(bucket, key);
        if (isNull(metadata) || isNull(metadata.getEtag())) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        String etag = metadata.getEtag();

        // Already indexed for this exact version? Retrieval will answer every question from
        // OpenSearch regardless of file size, so this readiness check needs nothing from the raw
        // file at all -- skip extraction entirely rather than re-running it (re-transcribing
        // audio, re-converting a document to PDF) just to recompute a size the UI won't even
        // show once usingRetrieval is true (see file-chat.html: the usingRetrieval branch never
        // references charsUsed/totalChars). A cheap OpenSearch existence check, not the live
        // embedding-model ping ragAvailable() also makes -- there is no question yet to embed
        // here, so only OpenSearch itself needs to be reachable for this fast path to apply.
        if (this.openSearchRagClient.isEnabled() && this.openSearchRagClient.isIndexed(bucket, key, etag)) {
            Map<String, Object> readiness = new HashMap<>();
            readiness.put("truncated", false);
            readiness.put("usingRetrieval", true);
            readiness.put("charsUsed", 0);
            readiness.put("totalChars", 0);
            readiness.put("limit", promptFileCharsFor(agentConfig == null ? null : agentConfig.getProvider()));
            return new ResponseDto(SUCCESS, "Ready.", readiness);
        }

        String text = this.fileChatExtractionService.extractText(bucket, key, etag);
        if (isNull(text) || text.trim().isEmpty()) {
            return new ResponseDto(ERROR, this.unsupportedMessage(key));
        }
        // The prompt tells the model its view is cut short, but the person asking had no way to
        // know their "summarise this" covered only the opening section -- report it so the UI
        // can say so before they ask rather than after.
        //
        // Against the chosen agent's provider where one is known. Without an agent the smallest
        // limit is assumed, so the warning errs towards appearing when it might not be needed
        // rather than staying silent when it is.
        int limit = promptFileCharsFor(agentConfig == null ? null : agentConfig.getProvider());
        boolean overLimit = text.length() > limit;
        // RAG now runs for every file it can reach, not only ones over the limit -- so a file
        // under the limit is not truncated either way, and one over it is not truncated whenever
        // retrieval is available, not only when it happens to also be large. ragAvailable() is a
        // live check (it calls the embedding model), so this reflects whether retrieval would
        // genuinely run for THIS request, not whether it is configured in principle.
        boolean willUseRetrieval = this.ragAvailable();
        Map<String, Object> readiness = new HashMap<>();
        readiness.put("truncated", overLimit && !willUseRetrieval);
        readiness.put("usingRetrieval", willUseRetrieval);
        readiness.put("charsUsed", willUseRetrieval ? text.length() : Math.min(text.length(), limit));
        readiness.put("totalChars", text.length());
        readiness.put("limit", limit);
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
        // Enforced here too, not just in prepareContext: prepareContext's block is a UI
        // convenience that runs when the panel opens or the agent selection changes, but
        // sendMessage is the actual API contract -- nothing stops a client calling it directly,
        // and an agent set up for spreadsheets must not be allowed to answer for a PDF just
        // because some caller skipped the readiness check.
        if (!acceptsFileType(config.getTargetFileTypes(), dto.getKey())) {
            return new ResponseDto(ERROR, fileTypeMismatchMessage(config.getTargetFileTypes(), dto.getKey()));
        }
        String provider = config.getProvider();
        String model = config.getModel();
        String apiKey = config.getApiKey();
        String apiEndpoint = config.getApiEndpoint();

        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadataCached(dto.getBucket(), dto.getKey());
        if (isNull(metadata) || isNull(metadata.getEtag())) {
            return new ResponseDto(ERROR, "Couldn't read this file's metadata.");
        }
        String etag = metadata.getEtag();
        // Deferred, not extracted here: when this exact bucket/key/etag is already indexed,
        // resolveContext never reads the raw file at all (see its javadoc) -- extracting first,
        // unconditionally, meant re-transcribing audio (tens of seconds) or re-converting a
        // document to PDF on every single question about an already-indexed file, for a value
        // nothing downstream would use. Memoized so a genuinely unindexed file's extraction
        // still runs at most once even though resolveContext's fallback path can reference it
        // after its own inner try/catch already saw (and needed) it once.
        FileContext context;
        try {
            context = this.resolveContext(dto.getBucket(), dto.getKey(), etag,
                this.memoizedExtraction(dto.getBucket(), dto.getKey(), etag), provider, dto.getMessage());
        } catch (UnsupportedFileTypeException ex) {
            return new ResponseDto(ERROR, ex.getMessage());
        }
        String instructions = this.buildInstructions(dto.getBucket(), dto.getKey(), context,
            dto.getHistory(), config.getInstructions());

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

    /**
     * What actually goes in the prompt as "the file": the whole extracted text when it already
     * fits the provider's budget, the top-K retrieved chunks when RAG ran, or a plain truncation
     * of the whole text when the file is too large and RAG could not run.
     */
    private static final class FileContext {
        final String content;
        final boolean retrieved;
        final boolean truncated;
        /** Only meaningful when retrieved: whether some of the file's chunks were left out. */
        final boolean partial;

        FileContext(String content, boolean retrieved, boolean truncated, boolean partial) {
            this.content = content;
            this.retrieved = retrieved;
            this.truncated = truncated;
            this.partial = partial;
        }
    }

    /**
     * Decides how much of the file the model actually sees, and does whatever that decision
     * requires -- an index-if-missing-then-retrieve round trip through OpenSearch whenever RAG
     * can run, for any file size; a plain truncation as the last resort when it cannot, exactly
     * as this method behaved for every file before RAG existed.
     *
     * RAG-first rather than large-file-only: the point of indexing a file at all is so a repeat
     * question -- about this file, in this session or the next one -- is answered from the
     * already-embedded chunks in OpenSearch instead of re-reading the raw extracted text, and
     * that reuse only exists for files that were indexed in the first place. A short file still
     * indexes cheaply (TextChunker hands back a single chunk when the whole file is smaller than
     * one), and {@code searchRelevantChunks} reports whether the retrieval it ran was complete --
     * see {@link #buildInstructions} for what that changes about the prompt.
     *
     * One retrieval query does double duty as the "is this indexed" check: a file's chunks are
     * ranked by relevance regardless of how relevant they are, so an indexed file with ANY
     * chunks always comes back non-empty -- an empty result reliably means "nothing indexed for
     * this exact bucket/key/etag yet", the same fact a separate isIndexed() count query would
     * have reported, one OpenSearch round trip earlier for every single message.
     */
    private FileContext resolveContext(String bucket, String key, String etag, TextSupplier fileTextSupplier,
        String provider, String question) throws Exception {
        int limit = promptFileCharsFor(provider);
        if (this.ragAvailable()) {
            try {
                float[] queryEmbedding = this.embeddingService.embed(question);
                OpenSearchRagClient.RetrievalResult result = this.openSearchRagClient.searchRelevantChunks(
                    bucket, key, etag, queryEmbedding, RAG_TOP_K);
                if (result.chunks.isEmpty()) {
                    // Nothing indexed for this exact file version yet. Serialized per
                    // bucket+key+etag -- see indexLockFor -- and re-checked once inside the lock,
                    // so a request that lost the race for the lock finds the winner's work already
                    // done instead of chunking/embedding/indexing the same file a second time. This
                    // is the ONLY branch that reads the raw file at all when RAG is healthy -- an
                    // already-indexed file (the common repeat-question case) never calls
                    // fileTextSupplier, which is the whole point of deferring it to a supplier
                    // instead of extracting unconditionally before this method is even called.
                    synchronized (this.indexLockFor(bucket, key, etag)) {
                        result = this.openSearchRagClient.searchRelevantChunks(
                            bucket, key, etag, queryEmbedding, RAG_TOP_K);
                        if (result.chunks.isEmpty()) {
                            List<String> chunks = TextChunker.chunk(fileTextSupplier.get());
                            if (!chunks.isEmpty()) {
                                List<float[]> embeddings = this.embeddingService.embedAll(chunks);
                                this.openSearchRagClient.indexChunks(TenantContext.getTenantId(), bucket, key,
                                    etag, chunks, embeddings, this.embeddingService.model());
                                logger.info("File Chat: indexed {} chunks for {}/{} (etag {}).",
                                    chunks.size(), bucket, key, etag);
                                result = this.openSearchRagClient.searchRelevantChunks(
                                    bucket, key, etag, queryEmbedding, RAG_TOP_K);
                            }
                        }
                    }
                } else {
                    logger.info("File Chat: {}/{} (etag {}) already indexed; reusing it.", bucket, key, etag);
                }
                if (!result.chunks.isEmpty()) {
                    return new FileContext(String.join("\n\n---\n\n", result.chunks), true, false, !result.complete);
                }
                logger.warn("File Chat: RAG retrieval returned nothing for {}/{}; falling back to direct content.",
                    bucket, key);
            } catch (UnsupportedFileTypeException ex) {
                // Not a RAG failure to degrade past -- the file itself has no readable content,
                // which the fallback below cannot fix either since it hits the same supplier.
                // Let the caller report it as what it actually is.
                throw ex;
            } catch (Exception ex) {
                // A RAG failure must degrade the answer, not the feature -- a tenant asking a
                // question about a file should not see an error because an embedding call timed
                // out once.
                logger.warn("File Chat: RAG pipeline failed for {}/{}, falling back to direct content: {}",
                    bucket, key, ex.getMessage());
            }
        }
        String fileText = fileTextSupplier.get();
        if (fileText.length() <= limit) {
            return new FileContext(fileText, false, false, false);
        }
        return new FileContext(fileText.substring(0, limit), false, true, false);
    }

    /** Thrown by a {@link TextSupplier} when the file has no readable content -- distinct from
        any other Exception so resolveContext's RAG-failure catch doesn't mistake "this file type
        genuinely isn't supported" for a transient infrastructure problem worth degrading past. */
    private static final class UnsupportedFileTypeException extends Exception {
        UnsupportedFileTypeException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    private interface TextSupplier {
        String get() throws Exception;
    }

    /**
     * A file's extracted text, fetched at most once no matter how many times resolveContext's
     * branches end up asking for it (the not-yet-indexed branch and the final fallback can both
     * reference the same supplier in one call). Extracting is the expensive, sometimes
     * genuinely slow step -- audio transcription runs tens of seconds per file -- so a caller
     * that never needs the raw text at all (the common already-indexed case) must never pay for
     * it, and one that does need it must never pay for it twice.
     */
    private TextSupplier memoizedExtraction(String bucket, String key, String etag) {
        String[] text = new String[1];
        Exception[] failure = new Exception[1];
        return () -> {
            if (failure[0] != null) {
                throw failure[0];
            }
            if (text[0] == null) {
                try {
                    String extracted = this.fileChatExtractionService.extractText(bucket, key, etag);
                    if (isNull(extracted) || extracted.trim().isEmpty()) {
                        throw new UnsupportedFileTypeException(this.unsupportedMessage(key));
                    }
                    text[0] = extracted;
                } catch (UnsupportedFileTypeException ex) {
                    failure[0] = ex;
                    throw ex;
                } catch (Exception ex) {
                    // extractText itself failing (a vision-model call erroring, a transcription
                    // failure, a storage read fault) is not a "RAG infrastructure hiccup" resolveContext
                    // can degrade past -- the raw-content fallback IS this same supplier, so there is
                    // nothing left to fall back to. Without this, the original exception (whatever type
                    // extractText happened to throw) gets cached and rethrown as-is on every subsequent
                    // call, including the direct-content fallback call sitting outside resolveContext's
                    // own try/catch -- escaping sendMessage entirely as an unhandled exception instead of
                    // the graceful "couldn't get content" answer every other extraction failure gets.
                    // Wrapping it here guarantees every failure this supplier can produce is a type
                    // sendMessage already knows how to turn into a plain ResponseDto(ERROR, ...).
                    logger.warn("File Chat: extraction failed for {}/{}: {}", bucket, key, ex.getMessage());
                    UnsupportedFileTypeException wrapped = new UnsupportedFileTypeException(
                        this.unsupportedMessage(key));
                    failure[0] = wrapped;
                    throw wrapped;
                }
            }
            return text[0];
        };
    }

    /**
     * The agent's own configured behaviour, prepended ahead of whatever category-specific prompt
     * follows -- persona, tone, domain focus, whatever the agent was set up for. Shared by every
     * prompt builder below rather than copy-pasted into each: a future change to how agent
     * instructions are framed (wording, a length cap) needs to land in one place, not be applied
     * to the document prompt and separately remembered for every other content category.
     */
    private void appendAgentPreamble(StringBuilder instructions, String agentInstructions) {
        if (!isNull(agentInstructions) && !agentInstructions.trim().isEmpty()) {
            instructions.append("Instructions for this agent, set by whoever configured it:\n")
                .append(agentInstructions.trim())
                .append("\n\n");
        }
    }

    /**
     * The last few turns of conversation, rendered the same way for every content category --
     * shared for the same reason as {@link #appendAgentPreamble}: one place to change the
     * truncation window or role labelling, not one per prompt builder.
     */
    private void appendHistory(StringBuilder instructions, List<FileChatHistoryItemDto> history) {
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
    }

    private String buildInstructions(String bucket, String key, FileContext context,
        List<FileChatHistoryItemDto> history, String agentInstructions) {
        // An image's or audio file's "content" is a vision/transcription model's description, not
        // extracted document text -- the export/download instructions below (CSV, PDF, Word...)
        // don't apply to either, and handing the model the bucket/path as an unconditional fact --
        // reasonable for a document someone might want to relocate -- turned into a vision model
        // volunteering the raw storage path and an offer to "download in a specific format"
        // unprompted, in a plain image description. A local model is exactly the kind that doesn't
        // reliably honour a soft "only mention this if asked" instruction, so the fix is to never
        // hand it the temptation: a materially shorter, purpose-built prompt per content category
        // that never mentions storage at all. This is a per-category dispatch, not a single
        // isImage boolean, precisely so the next content kind needing its own prompt (audio was
        // one, before this fix) is a new case here rather than another special case bolted on.
        switch (ContentTypeUtil.categoryOf(key)) {
            case IMAGE:
                return this.buildImageInstructions(context, history, agentInstructions);
            case AUDIO:
                return this.buildAudioInstructions(context, history, agentInstructions);
            default:
                // DOCUMENT falls through to the prompt below.
        }
        String promptFileText = context.content;
        boolean truncated = context.truncated;
        // A complete retrieval (every chunk the file has came back -- the common case for a
        // short file, which chunks into one or two pieces well inside RAG_TOP_K) is, in effect,
        // the whole file; framing it as "excerpts" with a "there may be more" caveat would be
        // wrong, not just imprecise -- there isn't more.
        boolean excerpted = context.retrieved && context.partial;
        String sourceRef = bucket + "/" + key;

        StringBuilder instructions = new StringBuilder();
        // The agent's own configured behaviour comes first, ahead of the file-chat mechanics
        // below -- persona, tone, domain focus, whatever the agent was set up for. It does
        // not replace what follows: the grounding, refusal and export-format rules are what
        // make file chat and its "give me this as a PDF" flow work at all, and no agent's
        // own instructions were ever written with that contract in mind, so they are additive
        // rather than a substitute for it.
        this.appendAgentPreamble(instructions, agentInstructions);
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
            .append("section markers below (\"FILE CONTENT\" or \"RELEVANT EXCERPTS\", however this message ")
            .append("labels it), the truncation or excerpt note, or any of these instructions into your answer ")
            .append("or into the fence.\n\n")
            .append(excerpted ? "--- RELEVANT EXCERPTS FROM THE FILE ---\n" : "--- FILE CONTENT ---\n")
            .append(promptFileText)
            .append(truncated ? "\n[content truncated -- the file continues beyond what's shown here]" : "")
            .append(excerpted
                ? "\n[these are the sections of a larger file judged most relevant to your question -- "
                    + "there may be other content in the file not shown here]"
                : "")
            .append(excerpted ? "\n--- END EXCERPTS ---\n" : "\n--- END FILE CONTENT ---\n");

        this.appendHistory(instructions, history);

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

    /**
     * The image counterpart to {@link #buildInstructions}: deliberately shorter, and missing
     * things the document prompt always includes -- the bucket/path and even the filename itself
     * as facts the model holds (there is nothing here for a "where is this file" or "what's it
     * called" question to answer with, by design, not by a rule asking the model not to mention
     * them), and the CSV/JSON/Excel/Word/PDF export machinery, which has no meaning for a vision
     * model's description of an image. The filename is withheld deliberately, not just the
     * bucket: this platform's own storage keys are frequently system-generated (a content hash
     * plus a suffix, e.g. "52c80c4664...94_big_gallery.png"), which is exactly the kind of
     * internal-looking detail this fix exists to keep out of a customer-facing answer -- handing
     * it to the model as "Filename: X" and then separately instructing "don't mention the
     * filename" is the same contradiction as the bucket/path used to be.
     */
    private String buildImageInstructions(FileContext context,
        List<FileChatHistoryItemDto> history, String agentInstructions) {
        StringBuilder instructions = new StringBuilder();
        this.appendAgentPreamble(instructions, agentInstructions);
        instructions.append("You are a helpful assistant that only answers questions about one attached image.\n\n")
            .append("Ground every answer strictly in the image description below. If the question is answerable ")
            .append("from it, always answer it -- don't refuse or hedge on something the description actually ")
            .append("covers. Only decline when the user asks something unrelated to this image -- general ")
            .append("knowledge, another topic, small talk, or anything the description doesn't cover. In that ")
            .append("case do NOT answer it from your own knowledge -- politely decline in a friendly, brief way: ")
            .append("apologize, explain you can only help with questions about this specific image, and invite ")
            .append("them to ask something about its content instead.\n\n")
            .append("This chat cannot produce downloadable files (CSV, PDF, Word, Excel, or any other format) for ")
            .append("an image -- if asked for one, say plainly that isn't available here and offer to describe or ")
            .append("answer in the chat instead. Never bring this up yourself; only address it if asked.\n\n")
            .append("Never mention, volunteer, or hint at where this file is stored -- no bucket name, path, ")
            .append("internal filename, hash, or URL -- even if one appears to be part of the image or its name. ")
            .append("You were not given that information for the purpose of repeating it, and nothing about ")
            .append("storage is relevant to describing what is in the image.\n\n")
            .append("--- IMAGE DESCRIPTION ---\n")
            .append(context.content)
            .append("\n--- END IMAGE DESCRIPTION ---\n");

        this.appendHistory(instructions, history);

        instructions.append("\nQuick reminder before you answer: (1) if the image description answers the ")
            .append("question, answer it -- don't refuse something it actually covers; (2) never mention where ")
            .append("this file is stored, and never offer to export or download it in any format -- those aren't ")
            .append("things this chat can do for an image.\n");
        return instructions.toString();
    }

    /**
     * The audio counterpart to {@link #buildImageInstructions} -- same reasoning, same shape: a
     * transcript is a model-generated rendering of the file's audio, not literal document text,
     * so it gets the same treatment as an image description rather than falling through to the
     * document prompt's bucket/path fact and CSV/Excel/PDF export machinery, neither of which
     * makes sense for a recording.
     */
    private String buildAudioInstructions(FileContext context,
        List<FileChatHistoryItemDto> history, String agentInstructions) {
        StringBuilder instructions = new StringBuilder();
        this.appendAgentPreamble(instructions, agentInstructions);
        instructions.append("You are a helpful assistant that only answers questions about one attached audio ")
            .append("recording.\n\n")
            .append("Ground every answer strictly in the transcript below. If the question is answerable from it, ")
            .append("always answer it -- don't refuse or hedge on something the transcript actually covers. Only ")
            .append("decline when the user asks something unrelated to this recording -- general knowledge, ")
            .append("another topic, small talk, or anything the transcript doesn't cover. In that case do NOT ")
            .append("answer it from your own knowledge -- politely decline in a friendly, brief way: apologize, ")
            .append("explain you can only help with questions about this specific recording, and invite them to ")
            .append("ask something about its content instead.\n\n")
            .append("This chat cannot produce downloadable files (CSV, PDF, Word, Excel, or any other format) for ")
            .append("an audio recording -- if asked for one, say plainly that isn't available here and offer to ")
            .append("describe or answer in the chat instead. Never bring this up yourself; only address it if ")
            .append("asked.\n\n")
            .append("Never mention, volunteer, or hint at where this file is stored -- no bucket name, path, ")
            .append("internal filename, hash, or URL -- even if one appears to be spoken in the recording or part ")
            .append("of its name. You were not given that information for the purpose of repeating it, and ")
            .append("nothing about storage is relevant to answering about the recording's content.\n\n")
            .append("--- AUDIO TRANSCRIPT ---\n")
            .append(context.content)
            .append("\n--- END AUDIO TRANSCRIPT ---\n");

        this.appendHistory(instructions, history);

        instructions.append("\nQuick reminder before you answer: (1) if the transcript answers the question, ")
            .append("answer it -- don't refuse something it actually covers; (2) never mention where this file is ")
            .append("stored, and never offer to export or download it in any format -- those aren't things this ")
            .append("chat can do for a recording.\n");
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
