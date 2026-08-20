package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jodconverter.core.DocumentConverter;
import org.jodconverter.core.document.DocumentFormat;
import org.jodconverter.core.document.DocumentFormatRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.service.AudioTranscriptService;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.util.ContentTypeUtil;
import process.util.DocumentConverterFormatRegistry;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import static process.util.ProcessUtil.*;

@Service
public class FileChatExtractionServiceImpl implements FileChatExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(FileChatExtractionServiceImpl.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private static final int MAX_TEXT_CHARS = 60000;

    private static final Set<String> NATIVE_TEXT_EXTENSIONS =
        new HashSet<>(Arrays.asList("md", "txt", "csv", "json", "xml"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList("mp3", "m4a"));

    @Value("${ollama.base.url:http://host.docker.internal:11434}")
    private String ollamaBaseUrl;

    @Value("${ollama.vision.model:llava}")
    private String visionModel;

    private final StorageBrowserService storageBrowserService;
    private final AudioTranscriptService audioTranscriptService;
    private final DocumentConverter documentConverter;
    private final DocumentFormatRegistry documentFormatRegistry;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(90, TimeUnit.SECONDS)
        .build();

    private final Gson gson = new Gson();

    public FileChatExtractionServiceImpl(StorageBrowserService storageBrowserService,
        AudioTranscriptService audioTranscriptService,
        DocumentConverter documentConverter,
        DocumentFormatRegistry documentFormatRegistry) {
        this.storageBrowserService = storageBrowserService;
        this.audioTranscriptService = audioTranscriptService;
        this.documentConverter = documentConverter;
        this.documentFormatRegistry = documentFormatRegistry;
    }

    @Override
    @Cacheable(value = "fileChatExtract", key = "#bucket + ':' + #key + ':' + #etag")
    public String extractText(String bucket, String key, String etag) throws Exception {
        String extension = ContentTypeUtil.extensionOf(key);

        if (NATIVE_TEXT_EXTENSIONS.contains(extension)) {
            return this.truncate(new String(this.readAllBytes(bucket, key), StandardCharsets.UTF_8));
        }
        if (AUDIO_EXTENSIONS.contains(extension)) {
            return this.transcribeAudio(bucket, key);
        }
        if ("pdf".equals(extension)) {
            byte[] pdfBytes = this.readAllBytes(bucket, key);
            String text = this.extractFromPdfBytes(pdfBytes);
            return (text != null && !text.trim().isEmpty()) ? text : this.describeFirstPageViaVisionModel(pdfBytes);
        }

        DocumentConverterFormatRegistry.FormatFamily family = DocumentConverterFormatRegistry.familyOfInput(extension);
        if (family == null) {
            return null;
        }
        byte[] pdfBytes = this.convertToPdf(bucket, key, extension);
        String text = this.extractFromPdfBytes(pdfBytes);
        if (text != null && !text.trim().isEmpty()) {
            return text;
        }

        return this.describeFirstPageViaVisionModel(pdfBytes);
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

    private byte[] convertBytes(byte[] sourceBytes, String sourceExtension, String targetExtension, String logLabel) throws Exception {
        DocumentFormat sourceFormat = this.documentFormatRegistry.getFormatByExtension(sourceExtension);
        DocumentFormat targetFormat = this.documentFormatRegistry.getFormatByExtension(targetExtension);
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

    private String extractFromPdfBytes(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return this.truncate(new PDFTextStripper().getText(document));
        } catch (Exception ex) {
            logger.warn("File Chat: PDFBox text extraction failed: {}", ex.getMessage());
            return null;
        }
    }

    private String describeFirstPageViaVisionModel(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            if (document.getNumberOfPages() == 0) {
                return null;
            }
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage pageImage = renderer.renderImageWithDPI(0, 120, ImageType.RGB);
            ByteArrayOutputStream pngBytes = new ByteArrayOutputStream();
            ImageIO.write(pageImage, "png", pngBytes);
            String base64Image = Base64.getEncoder().encodeToString(pngBytes.toByteArray());
            return this.callVisionModel(base64Image);
        } catch (Exception ex) {
            logger.warn("File Chat: vision-model fallback failed: {}", ex.getMessage());
            return "This file appears to be image-only (no extractable text), and the vision "
                + "model that would normally describe it isn't available right now.";
        }
    }

    private String callVisionModel(String base64Image) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", this.visionModel);
        body.addProperty("prompt", "Describe everything visible in this document image in detail, "
            + "including any text you can read. Transcribe text exactly where possible.");
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
            return this.truncate(json.has("response") ? json.get("response").getAsString() : null);
        }
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
        return this.truncate(String.valueOf(response.getData()));
    }

    private String truncate(String text) {
        if (isNull(text)) {
            return null;
        }
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }

}
