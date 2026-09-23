package process.media.extraction;

import process.media.UnreadableFileException;
import com.google.gson.Gson;
import process.billing.MeterClient;
import process.security.TenantContext;
import process.billing.Meter;
import process.billing.UsageEvent;
import org.springframework.beans.factory.annotation.Autowired;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.media.transcript.AudioTranscriptService;
import process.media.extraction.ExtractionService;
import process.model.service.StorageBrowserService;
import process.util.ContentTypeUtil;
import process.media.converter.DocumentConverterFormatRegistry;
import process.media.converter.MarkdownDocumentFormat;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static process.util.ProcessUtil.*;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipException;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class ExtractionServiceImpl implements ExtractionService {

    /** The meter, when the console has one; optional so hand-built instances in tests need none. */
    @Autowired(required = false)
    private MeterClient meter;


    private static final Logger logger = LoggerFactory.getLogger(ExtractionServiceImpl.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /**
     * The hard ceiling on how much text one file can contribute, whatever it was extracted from.
     *
     * The number's only job is to sit ABOVE the largest per-provider prompt budget in
     * FileChatServiceImpl (PROMPT_FILE_CHARS_BY_PROVIDER -- 400,000 for Anthropic, 250,000 for
     * the two OpenAI flavours today). It used to be 60,000, below every one of them, and that
     * inverted which of the two limits was actually in charge: a 500,000-character PDF arrived at
     * FileChatServiceImpl as exactly 60,000 characters, so resolveContext compared 60,000 against
     * 400,000, concluded nothing had been cut, and built a FileContext with truncated=false. The
     * prompt therefore carried no "[content truncated]" note and file-chat.html rendered no
     * warning banner, while 440,000 characters of the document had already been discarded here.
     * The model is told to ground its answers strictly in the file and never to pretend the file
     * covers something it does not, so it went on to deny -- confidently, and with no way for the
     * reader to tell -- content that this class had thrown away. The same capped string is what
     * gets chunked and embedded, so retrieval could not reach past 60,000 either.
     *
     * A ceiling still exists because the extracted text is cached, chunked and embedded one chunk
     * at a time, and an unbounded one turns a multi-hundred-megabyte log file into a very long
     * sequence of embedding calls. But it is now a backstop against a pathological file rather
     * than the thing that routinely decides what the model reads, and when it does bite, what
     * reaches FileChatServiceImpl is longer than any provider's budget -- so the truncation is
     * detected and reported instead of disappearing.
     */
    private static final int MAX_TEXT_CHARS = 500000;

    /**
     * Read as-is, no conversion. The list used to stop at md/txt/csv/json/xml, so a worker's
     * .log -- the file the "Log triage" prompt exists for -- fell through to the document
     * converter, which knows no such format, and the reader was told a 216-byte text file had
     * no readable content. Everything here is plain text by construction; none of it has a
     * converter family to lose by skipping the converter.
     */
    private static final Set<String> NATIVE_TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
        "md", "txt", "csv", "json", "xml",
        "log", "tsv", "yaml", "yml", "jsonl", "ndjson", "properties", "ini", "sql", "toml", "env"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList("mp3", "m4a"));

    /**
     * The cache both kinds of extraction land in, and the two key shapes that keep them apart.
     *
     * RedisConfig gives "fileChatExtract" a seven-day TTL. TWO entries per file rather than one,
     * because the two halves of this class answer to different things:
     *
     *   bucket:key:etag                       the file's OWN text -- a PDF text layer, an audio
     *                                         transcription, a decompressed log. Identical no
     *                                         matter which agent asked, so it stays keyed on the
     *                                         file alone and one agent's extraction is reused by
     *                                         every other one, transcriptions included.
     *
     *   bucket:key:etag:vision:MODEL:HASH     a vision model's DESCRIPTION of page 1, which
     *                                         depends entirely on which model looked at the
     *                                         pixels and what it was told to look for. Agent
     *                                         1022 "Vision Assistant" runs gemma3:4b with pages
     *                                         of medical-imaging instructions; a general agent
     *                                         runs the default llava:7b with the built-in
     *                                         prompt. Keyed on bucket:key:etag alone -- which is
     *                                         what this class did -- the first agent to open a
     *                                         scan decided for seven days what every other agent
     *                                         would be told that file contains.
     *
     * Read and written by hand rather than through @Cacheable, and that is not a style
     * preference: ONE call has to be able to touch BOTH entries (a PDF is tried as text first
     * and only then as an image), and a @Cacheable method calling a second @Cacheable method on
     * the same bean is a plain Java call that never passes through the caching proxy -- the
     * second annotation would sit there looking correct and cache nothing at all. The text key
     * is spelled exactly as the @CacheEvict expression on {@link #forgetExtraction} spells it,
     * and that is load-bearing: the two have to name the same entry.
     */
    private static final String CACHE_NAME = "fileChatExtract";

    /**
     * What a vision model is asked when the agent has nothing of its own to say.
     *
     * Was inlined in the request body, which is how it came to be the ONLY thing any vision
     * model was ever told -- see {@link #visionPrompt}.
     */
    private static final String DEFAULT_VISION_PROMPT =
        "Describe everything visible in this document image in detail, including any text you "
            + "can read. Transcribe text exactly where possible.";

    @Value("${ollama.base.url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    // Bare "llava" is a family name, not a pullable tag -- Ollama resolves it to nothing on its
    // own. "llava:7b" is what this project actually has pulled (see docker-compose.yml's
    // OLLAMA_VISION_MODEL, which sets the same default for the deployed container); this default
    // only matters for a run outside that compose file.
    @Value("${ollama.vision.model:llava:7b}")
    private String visionModel;

    /**
     * Injected on the field, and optional, on purpose.
     *
     * Optional because every unit test around this class builds it with {@code new} and no
     * Spring context at all; a missing cache manager has to mean "extract every time", not a
     * NullPointerException on the first file. On the field rather than the constructor because
     * the four-argument constructor is what those tests call, and widening it would break them
     * for a dependency none of them need.
     */
    @Autowired(required = false)
    private CacheManager cacheManager;

    private final StorageBrowserService storageBrowserService;
    private final AudioTranscriptService audioTranscriptService;
    private final DocumentConverter documentConverter;
    private final DocumentFormatRegistry documentFormatRegistry;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(90, TimeUnit.SECONDS)
        .build();

    private final Gson gson = new Gson();

    public ExtractionServiceImpl(StorageBrowserService storageBrowserService,
        AudioTranscriptService audioTranscriptService,
        DocumentConverter documentConverter,
        DocumentFormatRegistry documentFormatRegistry) {
        this.storageBrowserService = storageBrowserService;
        this.audioTranscriptService = audioTranscriptService;
        this.documentConverter = documentConverter;
        this.documentFormatRegistry = documentFormatRegistry;
    }

    /**
     * The no-agent entry point, unchanged for every caller that has no agent to offer: the
     * configured default vision model and the built-in prompt. A delegation rather than a second
     * copy of the flow, so the two can never drift apart.
     */
    @Override
    public String extractText(String bucket, String key, String etag) throws Exception {
        return this.extractText(bucket, key, etag, null, null);
    }

    // Parameters named for the agent rather than "visionModel"/"visionInstructions" so nothing
    // here reads as the configured default field of the same name -- the two are resolved
    // against each other exactly once, in describeViaVisionModel.
    @Override
    public String extractText(String bucket, String key, String etag,
        String agentVisionModel, String agentVisionInstructions) throws Exception {
        // The text entry only. A cached vision description is looked up further down, under its
        // own key, because only the path that needs one knows which model and instructions it
        // would have to match.
        String textKey = textCacheKey(bucket, key, etag);
        String cachedText = this.cachedExtraction(textKey);
        if (cachedText != null) {
            return cachedText;
        }
        String extension = ContentTypeUtil.extensionOf(key);

        if (NATIVE_TEXT_EXTENSIONS.contains(extension)) {
            return this.rememberExtraction(textKey,
                this.truncate(new String(this.readAllBytes(bucket, key), StandardCharsets.UTF_8), key));
        }
        // Gzipped text is common in log storage -- CloudTrail writes every digest and log file
        // as .json.gz -- so unwrap one layer and judge the file by what's inside it. Anything
        // that isn't text once decompressed still falls through to the unsupported path below.
        if ("gz".equals(extension)) {
            return this.rememberExtraction(textKey, this.extractGzip(bucket, key));
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return this.rememberExtraction(textKey, this.transcribeAudio(bucket, key));
        }

        byte[] pdfBytes;
        if ("pdf".equals(extension)) {
            pdfBytes = this.readAllBytes(bucket, key);
        } else {
            DocumentConverterFormatRegistry.FormatFamily family = DocumentConverterFormatRegistry.familyOfInput(extension);
            if (family == null) {
                // No reader by name. Before giving up, look at the bytes: a file called NOTES, a
                // .conf, a .dat that is really CSV -- an ETL bucket is full of text under names
                // nobody registered. Genuinely binary content (parquet, a zip) still answers
                // null, which is the one case where "not supported" is the literal truth.
                byte[] bytes = this.readAllBytes(bucket, key);
                if (looksLikeText(bytes)) {
                    return this.rememberExtraction(textKey, this.truncate(new String(bytes, StandardCharsets.UTF_8), key));
                }
                return null;
            }
            pdfBytes = this.convertToPdf(bucket, key, extension);
            if (pdfBytes == null || pdfBytes.length == 0) {
                // convertBytes already logged why. Null rather than a vision attempt: there is
                // no rendered page to look at, so asking the vision model would only produce a
                // second, misleading failure on top of the conversion one.
                return null;
            }
        }
        String text = this.extractFromPdfBytes(pdfBytes, key);
        if (text != null && !text.trim().isEmpty()) {
            return this.rememberExtraction(textKey, text);
        }
        return this.describeViaVisionModel(bucket, key, etag, pdfBytes, agentVisionModel, agentVisionInstructions);
    }

    /**
     * Decompresses a .gz object and extracts whatever it turns out to contain, decided by the
     * extension underneath (report.json.gz -> json). A file named only "*.gz" carries no inner
     * hint, so it is read as plain text, which is right for the log formats this exists for.
     */
    private String extractGzip(String bucket, String key) throws Exception {
        String innerName = key.substring(0, key.length() - ".gz".length());
        String innerExtension = ContentTypeUtil.extensionOf(innerName);
        byte[] compressed = this.readAllBytes(bucket, key);
        byte[] decompressed;
        try (GZIPInputStream gzip =
                 new GZIPInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            // Stop once there is comfortably more than truncate() will keep, so a multi-GB
            // archive can't be pulled into memory just to throw most of it away.
            while ((read = gzip.read(buffer)) != -1 && out.size() <= MAX_TEXT_CHARS * 4) {
                out.write(buffer, 0, read);
            }
            decompressed = out.toByteArray();
        } catch (ZipException e) {
            logger.warn("File Chat: {} has a .gz name but isn't valid gzip: {}", key, e.getMessage());
            return null;
        }
        if (innerExtension.isEmpty() || NATIVE_TEXT_EXTENSIONS.contains(innerExtension)) {
            return this.truncate(new String(decompressed, StandardCharsets.UTF_8), key);
        }
        logger.warn("File Chat: {} decompresses to .{}, which isn't a supported text type.", key, innerExtension);
        return null;
    }

    private byte[] readAllBytes(String bucket, String key) throws Exception {
        ObjectContentDto content = this.storageBrowserService.downloadObject(bucket, key, null, null);
        try (InputStream inputStream = content.getContent()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = inputStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        }
    }

    private byte[] convertToPdf(String bucket, String key, String inputExtension) throws Exception {
        byte[] sourceBytes = this.readAllBytes(bucket, key);
        return this.convertBytes(sourceBytes, inputExtension, "pdf", key);
    }

    @Override
    public byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception {
        if (!DocumentConverterFormatRegistry.isSupportedConversion(sourceExtension, targetExtension)) {
            logger.warn("File Chat: unsupported export conversion .{} -> .{}", sourceExtension, targetExtension);
            return null;
        }
        return this.convertBytes(content, sourceExtension, targetExtension, "chat export");
    }

    /** Markdown isn't in JODConverter's registry -- see MarkdownDocumentFormat. */
    private DocumentFormat resolveFormat(String extension) {
        if (MarkdownDocumentFormat.isMarkdown(extension)) {
            return MarkdownDocumentFormat.get();
        }
        return this.documentFormatRegistry.getFormatByExtension(extension);
    }

    private byte[] convertBytes(byte[] sourceBytes, String sourceExtension, String targetExtension, String logLabel) throws Exception {
        DocumentFormat sourceFormat = this.resolveFormat(sourceExtension);
        DocumentFormat targetFormat = this.resolveFormat(targetExtension);
        if (sourceFormat == null || targetFormat == null) {
            logger.warn("File Chat: no JODConverter format registered for .{} -> .{}", sourceExtension, targetExtension);
            return null;
        }
        Path tempDir = Files.createTempDirectory("file-chat-");
        Path tempSourceFile = tempDir.resolve("source." + sourceExtension);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            Files.write(tempSourceFile, sourceBytes);
            this.documentConverter.convert(tempSourceFile.toFile()).as(sourceFormat).to(outputStream).as(targetFormat).execute();
            return outputStream.toByteArray();
        } catch (Exception conversionException) {
            logger.warn("File Chat: conversion failed for {} (.{} -> .{}): {}", logLabel, sourceExtension, targetExtension, conversionException.getMessage());
            return null;
        } finally {
            try {
                Files.deleteIfExists(tempSourceFile);
                Files.deleteIfExists(tempDir);
            } catch (Exception cleanupException) {
                logger.warn("File Chat: failed to clean up temp conversion file {}: {}", tempSourceFile, cleanupException.getMessage());
            }
        }
    }

    /**
     * The PDF's own text layer, or null when it has none worth speaking of.
     *
     * The encryption catch is not defensive tidiness. {@code PDDocument.load} throws
     * InvalidPasswordException for a PDF that carries a user password, and under the blanket
     * catch below it that became one "PDFBox text extraction failed" log line and a null -- so
     * the caller moved on to the vision fallback, which loads the same bytes and fails the same
     * way, and the reader was finally told the file had no readable content, i.e. that their
     * perfectly ordinary encrypted PDF was somehow unreadable rubbish. The one thing nobody was
     * told is the one thing that was actually true and that the reader could have done something
     * about: it is locked.
     */
    private String extractFromPdfBytes(byte[] pdfBytes, String key) throws UnreadableFileException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return this.truncate(new PDFTextStripper().getText(document), key);
        } catch (InvalidPasswordException ex) {
            logger.warn("File Chat: {} is password-protected, so PDFBox cannot open it: {}", key, ex.getMessage());
            throw new UnreadableFileException("This PDF is password-protected, so nothing can be read out "
                + "of it here -- not its text and not its pages. Open it with its password and save an "
                + "unprotected copy if you need to ask questions about it.");
        } catch (Exception ex) {
            logger.warn("File Chat: PDFBox text extraction failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * The vision description of page 1, on its own cache entry.
     *
     * The separate entry is the whole point: this is the only part of extraction that depends on
     * WHO is asking. See CACHE_NAME for the two key shapes and why the split exists.
     */
    private String describeViaVisionModel(String bucket, String key, String etag, byte[] pdfBytes,
        String agentVisionModel, String agentVisionInstructions) throws Exception {
        // this.visionModel is the deployment-wide ${ollama.vision.model}; the agent's own model
        // wins whenever it has one. The resolved name, not the raw argument, goes into the key --
        // otherwise "no model configured" and "configured to the default" would be two different
        // cache entries holding identical descriptions.
        String model = isBlankText(agentVisionModel) ? this.visionModel : agentVisionModel.trim();
        String visionKey = visionCacheKey(bucket, key, etag, model, agentVisionInstructions);
        String cachedDescription = this.cachedExtraction(visionKey);
        if (cachedDescription != null) {
            return cachedDescription;
        }
        String description = this.describeFirstPageViaVisionModel(pdfBytes, key, model, agentVisionInstructions);
        if (description == null) {
            return null;
        }
        // Recorded before it is stored, never after: forgetExtraction is told which FILE to
        // forget and nothing about the agent, so the only way it can drop this entry is if the
        // key was written down first. A description still readable for seven days after the
        // panel that produced it was closed is exactly what that eviction exists to prevent.
        this.rememberVisionKey(bucket, key, etag, visionKey);
        // One image described by the vision model, once per (file, agent) -- the cache above
        // is why the same file opened twice is not two calls, and the key is the same reason
        // it is not two events.
        if (this.meter != null && TenantContext.getTenantId() != null) {
            this.meter.report(UsageEvent.of(TenantContext.getTenantId(), Meter.AI_VISION_IMAGES, 1, "vision#" + visionKey)
                .subject("object", bucket + "/" + key).actor(TenantContext.getAppUserId()).source("console"));
        }
        return this.rememberExtraction(visionKey, description);
    }

    /**
     * Renders page 1 and asks a vision model what is on it.
     *
     * Returns null ONLY when there is no page to render. It used to return, out of a blanket
     * catch, the sentence "This file appears to be image-only (no extractable text), and the
     * vision model that would normally describe it isn't available right now." -- as the file's
     * CONTENT. That sentence is not null, so the cache stored it for seven days as the text of
     * the document, and FileChatServiceImpl chunked and embedded it into the RAG index as though
     * it were the document: a scanned contract became a one-line file whose entire content was
     * an apology, and every later question about it was answered confidently, from cache,
     * against that apology -- including after the vision model came back up, because the etag
     * had not changed and so neither had the key. Failing now throws instead, so nothing is
     * cached and nothing is embedded, and the reader is told both halves of what happened: that
     * the file carries no text, and that the model which would have looked at it did not answer.
     *
     * Page 1 and only page 1, because there is no OCR anywhere in this project -- no Tika, no
     * tess4j, no tesseract; pdfbox is the only PDF dependency in the pom. See
     * {@link #prefixPagesNotRead} for why that has to be said out loud in the text itself.
     */
    private String describeFirstPageViaVisionModel(byte[] pdfBytes, String key,
        String model, String instructions) throws UnreadableFileException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        int pageCount;
        String base64Image;
        // No InvalidPasswordException branch here on purpose: an encrypted PDF never reaches
        // this method. extractFromPdfBytes loads the same bytes first and turns that exception
        // into the password message before a fallback is even considered.
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return null;
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage pageImage = renderer.renderImageWithDPI(0, 120, ImageType.RGB);
            ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
            ImageIO.write(pageImage, "png", pngBytes);
            base64Image = Base64.getEncoder().encodeToString(pngBytes.toByteArray());
        } catch (Exception ex) {
            logger.warn("File Chat: could not render page 1 of {} for the vision model: {}", key, ex.getMessage());
            throw new UnreadableFileException(noVisionDescriptionMessage(key, false));
        }

        String description;
        try {
            description = this.callVisionModel(base64Image, key, model, instructions);
        } catch (Exception ex) {
            logger.warn("File Chat: vision model {} did not answer for {}: {}", model, key, ex.getMessage());
            throw new UnreadableFileException(noVisionDescriptionMessage(key, false));
        }
        if (isBlankText(description)) {
            logger.warn("File Chat: vision model {} returned an empty description for {}", model, key);
            throw new UnreadableFileException(noVisionDescriptionMessage(key, true));
        }
        return prefixPagesNotRead(description, pageCount);
    }

    /**
     * Why nothing readable came back, in the two shapes it comes in, phrased for the person in
     * the chat panel.
     *
     * Both halves are stated deliberately -- that the file has no text to extract, AND what
     * happened when the picture of it was sent to a model. The reader's next move is different
     * for each ("wait and retry" versus "this page really is blank/undescribable"), and neither
     * is "give up on this file type", which is what the generic "couldn't get any readable
     * content out of this .pdf file" reads as.
     */
    private static String noVisionDescriptionMessage(String key, boolean modelAnswered) {
        String extension = ContentTypeUtil.extensionOf(key);
        String subject = extension.isEmpty() ? "This file" : "This ." + extension + " file";
        return subject + " has no text to extract -- it looks like a scan, a photo or an image-only "
            + "export -- and " + (modelAnswered
                ? "the vision model that looked at its first page had nothing to describe."
                : "the vision model that would describe its first page isn't answering right now, so "
                    + "nothing was read from it. Nothing has been stored for it, so it will be read "
                    + "again from scratch once that model is back.");
    }

    /**
     * Says, in the text itself, that only the first page was read.
     *
     * There is no OCR in this codebase -- no Tika, no tess4j, no tesseract, and pdfbox 2.0.31 is
     * the only PDF dependency in the pom -- so "reading" an image-only PDF means rendering page 0
     * and asking a vision model about that one image. A 40-page scanned report therefore produces
     * a description of its cover page, and that description was handed over as the file's whole
     * content: embedded into the RAG index as the document, dropped into the prompt under a
     * heading that says it is the file, and answered from by a model that is instructed to ground
     * itself strictly in what it is shown and never to pretend the file covers something it does
     * not. So "does this report mention X?" was answered "no" on the evidence of a cover page.
     *
     * The note lives in the text rather than in a log line because the text is the only thing
     * that travels: it reaches the prompt, the index and the reader; a log line reaches none of
     * them.
     */
    private static String prefixPagesNotRead(String description, int pageCount) {
        if (pageCount <= 1) {
            return description;
        }
        return "[Only page 1 of " + pageCount + " was read. This file has no text layer, so a vision model "
            + "described the first page image; the other " + (pageCount - 1) + " page(s) were not read at "
            + "all and nothing below reflects them. Do not treat this as the complete document.]\n\n"
            + description;
    }

    private String callVisionModel(String base64Image, String key,
        String model, String instructions) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", visionPrompt(instructions));
        body.addProperty("stream", false);
        JsonArray images = new JsonArray();
        images.add(base64Image);
        body.add("images", images);

        Request request = new Request.Builder()
            .url(this.ollamaBaseUrl + "/api/generate")
            .post(RequestBody.create(this.gson.toJson(body), JSON))
            .build();
        try (Response response = this.httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IllegalStateException(String.format("HTTP %d: %s", response.code(), responseBody));
            }
            JsonObject json = this.gson.fromJson(responseBody, JsonObject.class);
            return this.truncate(json.has("response") ? json.get("response").getAsString() : null, key);
        }
    }

    /**
     * What the vision model is actually told to do.
     *
     * The agent's own instructions, then the describe-and-transcribe request. Before this, the
     * prompt was the hardcoded second half alone: agent 1022 "Vision Assistant" carries 2,804
     * characters of medical-imaging instructions, and not one of them reached the model that
     * looked at the pixels -- they were only ever shown to the CHAT model afterwards, which by
     * then could see nothing but whatever a general-purpose llava:7b had thought worth
     * mentioning. Asking a radiology agent about a scan produced a description of "a black and
     * white image with some text", and the agent's instructions then dutifully discussed that.
     *
     * The generic half stays, appended rather than replaced, because whatever else the agent
     * wants, the description is the only record of this file that anything downstream will ever
     * see: it is what gets embedded, retrieved and answered from, so it still has to transcribe
     * the text on the page.
     */
    static String visionPrompt(String instructions) {
        if (isBlankText(instructions)) {
            return DEFAULT_VISION_PROMPT;
        }
        return instructions.trim() + "\n\n" + DEFAULT_VISION_PROMPT;
    }

    private String transcribeAudio(String bucket, String key) throws Exception {
        AudioExtractBucketRequestDto request = new AudioExtractBucketRequestDto();
        request.setBucket(bucket);
        request.setKey(key);
        request.setTimestamps(false);
        ResponseDto response = this.audioTranscriptService.extractFromBucket(request);
        if (!SUCCESS.equals(response.getStatus())) {
            throw new IllegalStateException(String.valueOf(response.getMessage()));
        }
        return this.truncate(String.valueOf(response.getData()), key);
    }

    /**
     * Applies MAX_TEXT_CHARS, and says so when it actually cuts something.
     *
     * The log line is the only record this cut ever leaves. {@code extractText} hands back a bare
     * String, so nothing downstream can tell a file that happened to be exactly MAX_TEXT_CHARS
     * long from one that was cut down to it, and the character totals the chat panel shows are
     * the post-cut ones. Reaching this at all now means a genuinely enormous file rather than an
     * ordinary PDF, so the line is rare enough to be worth reading when someone asks why an
     * answer stops partway through a document -- which is exactly the question nobody could
     * answer while the cap was 60,000 and silent.
     */
    /**
     * Whether bytes are text a person could read: valid UTF-8 over the first few KB, and no
     * NUL or stray control characters (tab, newline and return allowed). A conservative test:
     * a binary format that happens to pass it is far rarer than a text file under an odd name.
     */
    static boolean looksLikeText(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return true;
        }
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            int b = bytes[i] & 0xff;
            if (b == 0 || (b < 0x20 && b != '\t' && b != '\n' && b != '\r' && b != 0x0c)) {
                return false;
            }
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            // Cut at the sample edge could split a multi-byte character; back off up to 3 bytes.
            int end = n;
            for (int back = 0; back < 4 && end > 0; back++, end--) {
                try {
                    decoder.reset();
                    decoder.decode(ByteBuffer.wrap(bytes, 0, end));
                    return true;
                } catch (CharacterCodingException ex) {
                    if (end == bytes.length && bytes.length <= 8192) {
                        return false;
                    }
                }
            }
            return false;
        } finally {
            decoder.reset();
        }
    }

    private String truncate(String text, String key) {
        // A real null check. ProcessUtil.isNull is also true for "", which turned every
        // zero-byte text object into null -- the value that means "no reader for this type" --
        // so an empty transcript was reported as an unsupported format.
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_TEXT_CHARS) {
            return text;
        }
        logger.warn("File Chat: {} extracted to {} characters; keeping the first {}. The remainder is "
            + "neither sent to the model nor indexed for retrieval.", key, text.length(), MAX_TEXT_CHARS);
        return text.substring(0, MAX_TEXT_CHARS);
    }

    /** Null, empty and whitespace-only alike. ProcessUtil.isNull covers the first two only, and
        an agent whose instructions are a stray newline must land on the default prompt, not on a
        cache key of its own carrying a hash of "\n". */
    private static boolean isBlankText(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * The file's own text, keyed on the file alone -- spelled exactly as the {@link CacheEvict}
     * expression on {@link #forgetExtraction} spells it, because they have to name one entry.
     */
    static String textCacheKey(String bucket, String key, String etag) {
        return bucket + ":" + key + ":" + etag;
    }

    /**
     * A vision description, keyed on the file AND on what produced it.
     *
     * The model name and instruction hash are the whole fix. Two agents pointed at the same scan
     * are not asking the same question of it: gemma3:4b told to read a chest X-ray and llava:7b
     * told to describe a document image produce different text, and under the old file-only key
     * whichever ran first was served to both for seven days. The instructions are hashed rather
     * than embedded because they run to thousands of characters -- a Redis key is not the place
     * for an agent's entire prompt -- and hashed with SHA-256 rather than String.hashCode()
     * because the key has to stay stable across JVMs and versions: a key that moves with the
     * process means agent A's description is served to agent B after a restart, which is the bug
     * itself wearing a different hat.
     *
     * Package-private so the test can assert directly on the keys two agents generate, which is
     * the property that matters and is otherwise only observable through Redis.
     */
    static String visionCacheKey(String bucket, String key, String etag,
        String visionModel, String visionInstructions) {
        return textCacheKey(bucket, key, etag) + ":vision:"
            + (isBlankText(visionModel) ? "default" : visionModel.trim()) + ":"
            + instructionFingerprint(visionInstructions);
    }

    /** Where {@link #forgetExtraction} looks up the vision keys it would otherwise have no way
        of naming -- see its javadoc. */
    private static String visionIndexKey(String bucket, String key, String etag) {
        return textCacheKey(bucket, key, etag) + ":vision-index";
    }

    /**
     * A stable, short stand-in for an agent's instruction text.
     *
     * Trimmed first so that trailing whitespace edited in and out of the agent form does not
     * strand a perfectly good cached description behind a new key. Sixteen hex characters (64
     * bits) is far more than enough to separate the handful of agents a tenant configures.
     */
    static String instructionFingerprint(String instructions) {
        if (isBlankText(instructions)) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(instructions.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                hex.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // Every JRE is required to ship SHA-256. Rethrown rather than degraded to a weaker
            // fingerprint: a fingerprint that collides is precisely the cross-agent bug this
            // exists to stop, and failing loudly here is better than serving one agent's
            // description to another because a digest quietly fell back to something else.
            throw new IllegalStateException("SHA-256 is unavailable, so a vision description cannot "
                + "be keyed by the agent instructions that produced it", ex);
        }
    }

    /**
     * The cache, or null when there is none to speak of.
     *
     * Null in every unit test around this class (no Spring context, so nothing injects a manager)
     * and, in principle, in any deployment without one. Extraction has to work either way.
     */
    private Cache extractionCache() {
        return this.cacheManager == null ? null : this.cacheManager.getCache(CACHE_NAME);
    }

    /**
     * A cached extraction, or null for a miss -- and an unreachable cache is a miss.
     *
     * The swallow is deliberate and matches policy that already exists: RedisConfig registers
     * DegradeToMissCacheErrorHandler precisely so that an unreachable Redis becomes a miss rather
     * than a failed request. That handler is installed on Spring's CacheInterceptor, so it only
     * ever sees operations the @Cacheable machinery performs -- it cannot see a direct
     * CacheManager call like this one. Without the same treatment here, a Redis outage would come
     * out of extractText as a RedisConnectionFailureException, FileChatServiceImpl would turn it
     * into "Couldn't get any readable content out of this .pdf file", and a reader would be told
     * their document was unreadable because a cache was down.
     */
    private String cachedExtraction(String cacheKey) {
        Cache cache = this.extractionCache();
        if (cache == null) {
            return null;
        }
        try {
            return cache.get(cacheKey, String.class);
        } catch (RuntimeException ex) {
            logger.warn("File Chat: cache '{}' could not be read for key '{}' -- treating it as a miss "
                + "and extracting again.", CACHE_NAME, cacheKey, ex);
            return null;
        }
    }

    /**
     * Stores an extraction and hands it straight back, so a caller can {@code return} the call.
     *
     * Null is not stored and not an error -- that is the old {@code unless = "#result == null"}
     * spelled out, and it matters: a file type this class cannot read legitimately extracts to
     * null, and the Redis cache is configured to reject null values outright.
     *
     * A failed write is swallowed for the same reason DegradeToMissCacheErrorHandler swallows it:
     * the value is already extracted and on its way back to the caller, so losing the write costs
     * the next caller a re-extraction and nothing else.
     */
    private String rememberExtraction(String cacheKey, String value) {
        if (value == null) {
            return null;
        }
        Cache cache = this.extractionCache();
        if (cache != null) {
            try {
                cache.put(cacheKey, value);
            } catch (RuntimeException ex) {
                logger.warn("File Chat: cache '{}' could not be written for key '{}' -- the text was "
                    + "returned to the caller, just not cached.", CACHE_NAME, cacheKey, ex);
            }
        }
        return value;
    }

    /**
     * Notes that a vision entry exists for this file, so closing the chat can drop it.
     *
     * A newline-separated list rather than a collection, because the cache serializes values as
     * JSON and a plain String round-trips through that identically whatever the cache manager is.
     * Two agents describing the same file at the same instant can lose one of the two entries
     * from this list; the cost is one description surviving to its seven-day TTL instead of being
     * evicted at close, which is worth not putting a lock in the middle of an extraction.
     */
    private void rememberVisionKey(String bucket, String key, String etag, String visionKey) {
        String indexKey = visionIndexKey(bucket, key, etag);
        String recorded = this.cachedExtraction(indexKey);
        if (recorded == null) {
            this.rememberExtraction(indexKey, visionKey);
            return;
        }
        if (Arrays.asList(recorded.split("\n")).contains(visionKey)) {
            return;
        }
        this.rememberExtraction(indexKey, recorded + "\n" + visionKey);
    }

    /**
     * Forgets the text extracted from one file.
     *
     * Called when a chat panel closes. The extraction is the only trace a chat leaves on the
     * server -- nothing is written to the database and no transcript is logged -- and it is the
     * whole readable content of whatever was opened, which for this platform means CVs, intake
     * forms and anything else a tenant keeps in a bucket. Holding that for the cache's seven-day
     * ceiling after the person has finished reading it is longer than the work requires.
     *
     * Evicting costs the next chat on the same file a re-extraction. That is the trade being
     * made deliberately: the cache exists to make a conversation responsive, not to retain
     * document contents between conversations.
     *
     * The annotation drops the text entry. The vision entries cannot be reached that way: their
     * keys carry the model and instruction hash of whichever agent produced them, and this method
     * is told which FILE to forget and nothing at all about agents -- so they are looked up in
     * the index written alongside them. A vision description of a scanned passport is the same
     * kind of content as the text of a CV, and closing the panel has to forget both.
     */
    @Override
    @CacheEvict(value = CACHE_NAME, key = "#bucket + ':' + #key + ':' + #etag")
    public void forgetExtraction(String bucket, String key, String etag) {
        Cache cache = this.extractionCache();
        if (cache == null) {
            return;
        }
        String indexKey = visionIndexKey(bucket, key, etag);
        String recorded = this.cachedExtraction(indexKey);
        if (recorded != null) {
            for (String visionKey : recorded.split("\n")) {
                if (!visionKey.trim().isEmpty()) {
                    this.evictQuietly(cache, visionKey);
                }
            }
        }
        this.evictQuietly(cache, indexKey);
    }

    /**
     * An eviction that a dead cache cannot turn into a failed request.
     *
     * Same reasoning as DegradeToMissCacheErrorHandler's evict branch in RedisConfig, which this
     * direct CacheManager call does not go through: closing a chat panel has already succeeded by
     * the time this runs, and reporting it as failed because Redis is unreachable helps nobody.
     * The honest cost is that the entry then survives to its seven-day TTL.
     */
    private void evictQuietly(Cache cache, String cacheKey) {
        try {
            cache.evict(cacheKey);
        } catch (RuntimeException ex) {
            logger.warn("File Chat: cache '{}' could not evict key '{}' -- the entry will expire on its TTL.",
                CACHE_NAME, cacheKey, ex);
        }
    }
}
