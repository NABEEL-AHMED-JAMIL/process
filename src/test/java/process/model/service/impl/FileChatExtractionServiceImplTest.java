package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ObjectContentDto;
import process.model.service.AudioTranscriptService;
import process.model.service.FileChatExtractionService.UnreadableFileException;
import process.model.service.StorageBrowserService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a file chat is allowed to claim it read.
 *
 * Every test here is about the same class of bug: extraction inventing content, or hiding the
 * reason there is none. The extracted string is not a diagnostic -- it is cached for seven days
 * as the file's text, chunked and embedded into the RAG index as the document, and dropped into
 * the prompt under a heading that says it IS the file, in front of a model told to ground its
 * answers strictly in it. Anything untrue that reaches this return value is therefore not a
 * cosmetic wording problem; it is an answer the reader has no way to doubt.
 *
 * The four things pinned:
 *
 *   1. A failed vision call must not become the file's content. It used to return an apology
 *      sentence, which was cached and embedded as the document.
 *   2. A password-protected PDF must say it is password-protected, not be reported as an
 *      unreadable image.
 *   3. A multi-page scan must say that only page 1 was read. There is no OCR in this project.
 *   4. The agent's vision model and instructions must reach the model that looks at the pixels,
 *      and two agents must not share one cache entry.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class FileChatExtractionServiceImplTest {

    private static final String BUCKET = "docs";
    private static final String KEY = "scan.pdf";
    private static final String ETAG = "etag-1";
    private static final String CACHE = "fileChatExtract";

    /** Agent 1022 "Vision Assistant", shortened. */
    private static final String RADIOLOGY_INSTRUCTIONS =
        "You are a radiology assistant. Report findings by anatomical region and never speculate "
            + "about a diagnosis the image does not support.";
    private static final String CONTRACT_INSTRUCTIONS =
        "You are a contracts assistant. Transcribe clause numbers and parties exactly.";

    @Mock private StorageBrowserService storageBrowserService;
    @Mock private AudioTranscriptService audioTranscriptService;
    @Mock private DocumentConverter documentConverter;
    @Mock private DocumentFormatRegistry documentFormatRegistry;

    private HttpServer visionServer;
    private final List<String> visionRequests = Collections.synchronizedList(new ArrayList<>());
    private final Gson gson = new Gson();
    private CacheManager cacheManager;

    /** PDFBox renders page images through Java2D; a build agent with no display must not be the
        reason a test about vision fallbacks fails. */
    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @BeforeEach
    void freshCache() {
        this.cacheManager = new ConcurrentMapCacheManager(CACHE);
    }

    @AfterEach
    void stopVisionServer() {
        if (this.visionServer != null) {
            this.visionServer.stop(0);
            this.visionServer = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // FIX 1 -- a failed vision call is not content.
    // ---------------------------------------------------------------------------------------

    /**
     * The bug in one sentence: with the vision model down, a scanned PDF extracted to the
     * sentence "This file appears to be image-only (no extractable text), and the vision model
     * that would normally describe it isn't available right now." -- and that sentence, not being
     * null, was cached for seven days and embedded into the RAG index as the document's text.
     * Every later question about that contract was answered against the apology, confidently,
     * long after the model came back up: the etag had not changed, so neither had the cache key.
     */
    @Test
    void aDeadVisionModelNeverBecomesTheFilesContent() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstNothingListening(imageOnlyPdf(1));

        assertThatThrownBy(() -> service.extractText(BUCKET, KEY, ETAG))
            .as("a vision failure must be reported as a failure, never returned as the file's text")
            .isInstanceOf(UnreadableFileException.class)
            .hasMessageNotContaining("This file appears to be image-only");

        assertThat(this.cacheEntries())
            .as("nothing may be cached for a file nothing was read out of -- a cached apology is "
                + "re-served, and re-embedded, for seven days after the model recovers")
            .isEmpty();
    }

    /**
     * And the message itself, which is the half the reader actually sees.
     *
     * Null would have been honest about the content and silent about the cause: FileChatServiceImpl
     * turns a null extraction into "Couldn't get any readable content out of this .pdf file.",
     * which reads as "this file type isn't supported" for a supported file type whose vision model
     * happens to be down for the minute. Both facts have to survive to the panel -- that the file
     * carries no text of its own, AND that the model which would have looked at it did not answer.
     */
    @Test
    void theMessageSeparatesNoTextLayerFromVisionBeingUnavailable() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstNothingListening(imageOnlyPdf(1));

        assertThatThrownBy(() -> service.extractText(BUCKET, KEY, ETAG))
            .isInstanceOf(UnreadableFileException.class)
            .hasMessageContaining(".pdf")
            .hasMessageContaining("no text to extract")
            .hasMessageContaining("vision model");
    }

    /**
     * A vision model that answers, but with nothing in it, is the other half of the same rule:
     * an empty description is not a description, and must not be stored as one.
     */
    @Test
    void anEmptyDescriptionIsNotStoredAsTheFilesContent() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(imageOnlyPdf(1), "   ");

        assertThatThrownBy(() -> service.extractText(BUCKET, KEY, ETAG))
            .isInstanceOf(UnreadableFileException.class);
        assertThat(this.cacheEntries()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // FIX 2 -- a password-protected PDF says so.
    // ---------------------------------------------------------------------------------------

    /**
     * PDDocument.load throws InvalidPasswordException for an encrypted PDF. Under the blanket
     * catch that used to sit there, that became one "PDFBox text extraction failed" log line and
     * a null, the caller moved on to the vision fallback, the fallback loaded the same bytes and
     * failed the same way, and the reader was told their perfectly ordinary locked PDF was an
     * unreadable image. The one thing nobody was told is the only thing they could have acted on.
     */
    @Test
    void aPasswordProtectedPdfIsReportedAsLockedNotAsAnImage() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            passwordProtectedPdf(), "a description that must never be reached");

        assertThatThrownBy(() -> service.extractText(BUCKET, KEY, ETAG))
            .isInstanceOf(UnreadableFileException.class)
            .hasMessageContaining("password-protected");

        assertThat(this.visionRequests)
            .as("an encrypted PDF cannot be rendered either, so the vision model must not be "
                + "asked to look at one -- that round trip only produces a second, wrong reason")
            .isEmpty();
        assertThat(this.cacheEntries()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // FIX 3 -- only page 1 was read, and the text has to say so.
    // ---------------------------------------------------------------------------------------

    /**
     * There is no OCR in this project -- no Tika, no tess4j, no tesseract, pdfbox is the only PDF
     * dependency -- so a 40-page scan is "read" by rendering page 0 and asking a vision model
     * about that one image. That description was then handed over as the whole document: indexed
     * as the document, prompted as the document, and answered from by a model instructed never to
     * pretend the file covers something it does not. "Does this report mention X?" was answered
     * on the evidence of a cover page.
     */
    @Test
    void aMultiPageScanSaysOnlyPageOneWasRead() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            imageOnlyPdf(12), "Cover page of a quarterly report.");

        String extracted = service.extractText(BUCKET, KEY, ETAG);

        assertThat(extracted)
            .as("the note has to be in the TEXT: the text is the only thing that reaches the "
                + "prompt, the RAG index and the reader -- a log line reaches none of them")
            .startsWith("[Only page 1 of 12 was read.")
            .contains("11 page(s) were not read")
            .endsWith("Cover page of a quarterly report.");
    }

    /** A single-page file genuinely was read in full, so the warning would be a lie of its own. */
    @Test
    void aSinglePageScanCarriesNoSuchNote() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            imageOnlyPdf(1), "A signed delivery note.");

        String extracted = service.extractText(BUCKET, KEY, ETAG);

        assertThat(extracted).isEqualTo("A signed delivery note.");
    }

    // ---------------------------------------------------------------------------------------
    // FIX 4 -- the agent's model and instructions reach the pixels, on their own cache entry.
    // ---------------------------------------------------------------------------------------

    /**
     * THE key test. Extraction was cached on bucket:key:etag alone, so the first agent to open a
     * scan decided for seven days what every other agent would be told that file contains: a
     * radiology agent's reading of an X-ray served verbatim to a contracts agent, and the other
     * way round.
     *
     * Asserted on the keys themselves rather than only through the cache, because this is the
     * property the whole fix rests on and it is otherwise only visible inside Redis.
     */
    @Test
    void twoAgentInstructionSetsProduceTwoDifferentCacheKeys() {
        String radiology = FileChatExtractionServiceImpl.visionCacheKey(
            BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);
        String contracts = FileChatExtractionServiceImpl.visionCacheKey(
            BUCKET, KEY, ETAG, "gemma3:4b", CONTRACT_INSTRUCTIONS);

        assertThat(radiology)
            .as("same file, same model, different instructions -- different description, so it "
                + "cannot be the same cache entry")
            .isNotEqualTo(contracts);
        assertThat(FileChatExtractionServiceImpl.visionCacheKey(
                BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS))
            .as("and the key has to be stable, or nothing is ever served from cache at all")
            .isEqualTo(radiology);
    }

    /** The model half of the same key, for two agents that share instructions but not a model. */
    @Test
    void twoVisionModelsProduceTwoDifferentCacheKeys() {
        assertThat(FileChatExtractionServiceImpl.visionCacheKey(BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS))
            .isNotEqualTo(FileChatExtractionServiceImpl.visionCacheKey(BUCKET, KEY, ETAG, "llava:7b", RADIOLOGY_INSTRUCTIONS));
    }

    /**
     * The fingerprint is a SHA-256 digest rather than String.hashCode() on purpose: the key has
     * to mean the same thing in the next JVM, or a restart starts serving agent A's description
     * to agent B again -- the bug itself wearing a different hat. A hardcoded expectation is what
     * makes "stable" checkable at all; recomputing it with the same code would pass for any hash.
     */
    @Test
    void theInstructionFingerprintIsStableAcrossRuns() {
        assertThat(FileChatExtractionServiceImpl.instructionFingerprint("describe the chest x-ray"))
            .isEqualTo("ea2323fc3ffbb354");
        assertThat(FileChatExtractionServiceImpl.instructionFingerprint("  describe the chest x-ray  "))
            .as("whitespace edited into the agent form must not strand a good cached description")
            .isEqualTo("ea2323fc3ffbb354");
        assertThat(FileChatExtractionServiceImpl.instructionFingerprint(null))
            .isEqualTo(FileChatExtractionServiceImpl.instructionFingerprint("   "));
    }

    /**
     * Text extraction stays agent-independent, and the text key stays spelled the way
     * forgetExtraction's @CacheEvict expression spells it. If those two ever drift, closing a
     * chat panel silently stops forgetting the file -- the eviction would be evicting a key
     * nothing was ever stored under.
     */
    @Test
    void theTextKeyIsTheFileAloneAndMatchesTheEvictionExpression() {
        assertThat(FileChatExtractionServiceImpl.textCacheKey(BUCKET, KEY, ETAG))
            .isEqualTo(BUCKET + ":" + KEY + ":" + ETAG);
        assertThat(FileChatExtractionServiceImpl.visionCacheKey(BUCKET, KEY, ETAG, "llava:7b", null))
            .as("and a vision entry must never collide with the text one")
            .isNotEqualTo(FileChatExtractionServiceImpl.textCacheKey(BUCKET, KEY, ETAG));
    }

    /**
     * End to end, against a real (stub) vision endpoint: the agent's model and its instructions
     * are what actually reach the request, and one agent's description is never handed to
     * another. Before this, the request carried ${ollama.vision.model} and a hardcoded prompt --
     * agent 1022's 2,804 characters of medical-imaging instructions were shown only to the CHAT
     * model afterwards, which by then could see nothing but whatever a general-purpose llava:7b
     * had thought worth mentioning.
     */
    @Test
    void theAgentsModelAndInstructionsReachTheVisionCallAndAreNotShared() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            imageOnlyPdf(1), "left lower lobe opacity");

        String radiology = service.extractText(BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);
        this.visionResponse = "clause 4.2 termination for convenience";
        String contracts = service.extractText(BUCKET, KEY, ETAG, "llava:7b", CONTRACT_INSTRUCTIONS);

        assertThat(radiology).isEqualTo("left lower lobe opacity");
        assertThat(contracts)
            .as("the second agent must get its OWN description, not the first agent's from cache")
            .isEqualTo("clause 4.2 termination for convenience");

        assertThat(this.visionRequests).hasSize(2);
        JsonObject first = this.gson.fromJson(this.visionRequests.get(0), JsonObject.class);
        assertThat(first.get("model").getAsString())
            .as("the agent's own vision model has to be the one that looks at the pixels")
            .isEqualTo("gemma3:4b");
        assertThat(first.get("prompt").getAsString())
            .as("and the agent's instructions have to be in the prompt that goes with the image")
            .contains(RADIOLOGY_INSTRUCTIONS)
            .contains("Transcribe text exactly where possible");

        JsonObject second = this.gson.fromJson(this.visionRequests.get(1), JsonObject.class);
        assertThat(second.get("model").getAsString()).isEqualTo("llava:7b");
        assertThat(second.get("prompt").getAsString()).contains(CONTRACT_INSTRUCTIONS);
    }

    /** The cache still has to work for the agent it belongs to, or every message re-runs a local
        vision model that takes tens of seconds. */
    @Test
    void theSameAgentAsksTheVisionModelOnlyOnce() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            imageOnlyPdf(1), "left lower lobe opacity");

        service.extractText(BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);
        String second = service.extractText(BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);

        assertThat(second).isEqualTo("left lower lobe opacity");
        assertThat(this.visionRequests).hasSize(1);
    }

    /**
     * Closing the panel has to forget the vision descriptions too.
     *
     * Their keys carry the model and instruction hash of whichever agent produced them, and
     * forgetExtraction is told which FILE to forget and nothing about agents -- so without the
     * index written alongside each description, a vision reading of a scanned passport would sit
     * in Redis for its full seven days after the reader closed the chat, which is exactly what
     * that eviction exists to prevent. (The text entry is the @CacheEvict annotation's job and
     * needs a Spring proxy, so it is not asserted here.)
     */
    @Test
    void forgettingAFileDropsTheAgentKeyedDescriptionsToo() throws Exception {
        FileChatExtractionServiceImpl service = this.serviceAgainstVisionModel(
            imageOnlyPdf(1), "a scanned passport page");
        service.extractText(BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);
        String visionKey = FileChatExtractionServiceImpl.visionCacheKey(
            BUCKET, KEY, ETAG, "gemma3:4b", RADIOLOGY_INSTRUCTIONS);
        assertThat(this.cacheEntries()).containsKey(visionKey);

        service.forgetExtraction(BUCKET, KEY, ETAG);

        assertThat(this.cacheEntries())
            .as("a description of a scanned document is the same kind of content as the text of "
                + "a CV; closing the panel forgets both")
            .doesNotContainKey(visionKey);
    }

    // ---------------------------------------------------------------------------------------
    // Fixtures.
    // ---------------------------------------------------------------------------------------

    private String visionResponse = "";

    /** A PDF with pages but no text layer at all -- what a scanner produces, and what sends this
        class down the vision path. */
    private static byte[] imageOnlyPdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pages; page++) {
                // One inch square: this gets rendered for real at 120 DPI, and the test has no
                // interest in how many pixels that is.
                document.addPage(new PDPage(new PDRectangle(72f, 72f)));
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /** Encrypted with a USER password: an owner-password-only PDF opens perfectly well in PDFBox
        and would not exercise anything. */
    private static byte[] passwordProtectedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(72f, 72f)));
            StandardProtectionPolicy policy =
                new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * A worker's .log is plain text and reads as itself. It used to fall through to the document
     * converter -- which has no "log" family -- and come back as "no readable content", which is
     * the one answer a 216-byte text file cannot honestly be given. Same for the other plain
     * formats an ETL bucket fills up with.
     */
    @Test
    void plainTextFormatsWithoutAConverterFamilyAreReadAsThemselves() throws Exception {
        for (String name : new String[] {"worker.log", "rates.tsv", "config.yaml", "events.jsonl", "app.properties"}) {
            byte[] bytes = ("line one of " + name + "\nline two").getBytes(StandardCharsets.UTF_8);
            when(this.storageBrowserService.downloadObject(BUCKET, name, null, null))
                .thenAnswer(invocation -> new ObjectContentDto(new ByteArrayInputStream(bytes),
                    "text/plain", bytes.length, name));
            FileChatExtractionServiceImpl service = new FileChatExtractionServiceImpl(
                this.storageBrowserService, this.audioTranscriptService,
                this.documentConverter, this.documentFormatRegistry);
            ReflectionTestUtils.setField(service, "cacheManager", this.cacheManager);

            assertThat(service.extractText(BUCKET, name, "etag-" + name))
                .as(name + " should be read as text")
                .isEqualTo("line one of " + name + "\nline two");
        }
        verifyNoInteractions(this.documentConverter);
    }

    /** A zero-byte text object reads as "", never as null: null means "no reader for this type". */
    @Test
    void anEmptyTextFileReadsAsEmptyNotAsUnsupported() throws Exception {
        byte[] none = new byte[0];
        when(this.storageBrowserService.downloadObject(BUCKET, "tone.txt", null, null))
            .thenAnswer(invocation -> new ObjectContentDto(new ByteArrayInputStream(none), "text/plain", 0L, "tone.txt"));
        FileChatExtractionServiceImpl service = new FileChatExtractionServiceImpl(
            this.storageBrowserService, this.audioTranscriptService,
            this.documentConverter, this.documentFormatRegistry);
        ReflectionTestUtils.setField(service, "cacheManager", this.cacheManager);

        assertThat(service.extractText(BUCKET, "tone.txt", "etag-empty")).isEqualTo("");
    }

    private FileChatExtractionServiceImpl service(byte[] pdfBytes, String ollamaBaseUrl) throws Exception {
        // A fresh stream per call: extraction reads the object again for every agent that has
        // not already got a description cached, and a consumed ByteArrayInputStream would look
        // like an empty file rather than a second read.
        when(this.storageBrowserService.downloadObject(BUCKET, KEY, null, null))
            .thenAnswer(invocation -> new ObjectContentDto(new ByteArrayInputStream(pdfBytes),
                "application/pdf", pdfBytes.length, KEY));

        FileChatExtractionServiceImpl service = new FileChatExtractionServiceImpl(
            this.storageBrowserService, this.audioTranscriptService,
            this.documentConverter, this.documentFormatRegistry);
        ReflectionTestUtils.setField(service, "ollamaBaseUrl", ollamaBaseUrl);
        ReflectionTestUtils.setField(service, "visionModel", "llava:7b");
        ReflectionTestUtils.setField(service, "cacheManager", this.cacheManager);
        return service;
    }

    private FileChatExtractionServiceImpl serviceAgainstVisionModel(byte[] pdfBytes, String response) throws Exception {
        this.visionResponse = response;
        this.visionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.visionServer.createContext("/api/generate", this::handleVisionCall);
        this.visionServer.start();
        return this.service(pdfBytes, "http://127.0.0.1:" + this.visionServer.getAddress().getPort());
    }

    /** Ollama down, which is the ordinary state of affairs on a laptop that has not started it:
        a closed port, so the call fails immediately rather than waiting out a timeout. */
    private FileChatExtractionServiceImpl serviceAgainstNothingListening(byte[] pdfBytes) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        return this.service(pdfBytes, "http://127.0.0.1:" + closedPort);
    }

    private void handleVisionCall(HttpExchange exchange) throws IOException {
        try (InputStream requestStream = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = requestStream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            this.visionRequests.add(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        }
        JsonObject body = new JsonObject();
        body.addProperty("response", this.visionResponse);
        byte[] responseBytes = this.gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream responseStream = exchange.getResponseBody()) {
            responseStream.write(responseBytes);
        }
    }

    private ConcurrentMap<Object, Object> cacheEntries() {
        return ((ConcurrentMapCache) this.cacheManager.getCache(CACHE)).getNativeCache();
    }
}
