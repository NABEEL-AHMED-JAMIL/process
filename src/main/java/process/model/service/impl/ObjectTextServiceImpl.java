package process.model.service.impl;

import org.springframework.stereotype.Service;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectTextDto;
import process.model.dto.ResponseDto;
import process.model.service.FileChatExtractionService;
import process.model.service.StorageBrowserService;
import process.util.ContentTypeUtil;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Any object in any bucket the caller may read, as text a prompt variable can hold.
 *
 * One reader for every file type, and it is the file chat's: plain text and its cousins as
 * they are, .gz unwrapped, PDF and Office documents through the converter, an image or a
 * scanned page through the vision model, audio through the transcript service. A prompt tried
 * on a file therefore sees exactly what the chat on that file sees, so "it worked in the chat
 * but not in the step" cannot happen for a reason of reading.
 *
 * Authorisation is the storage browser's: the metadata read refuses a bucket or key the caller
 * may not reach before a byte is fetched.
 */
@Service
public class ObjectTextServiceImpl {

    /** A variable's worth. The chat keeps 500k for retrieval; a prompt renders the whole value
        into one request, so it is capped where a small model's context still fits. */
    public static final int MAX_CHARS = 60000;

    private final StorageBrowserService storageBrowserService;
    private final FileChatExtractionService extraction;

    public ObjectTextServiceImpl(StorageBrowserService storageBrowserService, FileChatExtractionService extraction) {
        this.storageBrowserService = storageBrowserService;
        this.extraction = extraction;
    }

    public ResponseDto read(String bucket, String key, Integer maxChars) {
        if (bucket == null || bucket.trim().isEmpty() || key == null || key.trim().isEmpty() || key.endsWith("/")) {
            return new ResponseDto(ERROR, "Name a bucket and a file in it.");
        }
        ObjectMetadataDto metadata = this.storageBrowserService.getObjectMetadata(bucket, key);
        if (metadata == null || metadata.getEtag() == null) {
            return new ResponseDto(ERROR, "Couldn't read that file's metadata.");
        }
        String extracted;
        try {
            extracted = this.extraction.extractText(bucket, key, metadata.getEtag());
        } catch (FileChatExtractionService.UnreadableFileException ex) {
            return new ResponseDto(ERROR, ex.getMessage());
        } catch (Exception ex) {
            return new ResponseDto(ERROR, String.format("%s could not be read: %s", ContentTypeUtil.fileNameOf(key), ex.getMessage()));
        }
        String extension = ContentTypeUtil.isGzip(key) ? ContentTypeUtil.innerExtensionOfGzip(key) : ContentTypeUtil.extensionOf(key);
        if (extracted == null) {
            return new ResponseDto(ERROR, extension.isEmpty()
                ? "This file type isn't supported."
                : String.format("There is no reader for .%s files yet.", extension));
        }
        if (extracted.trim().isEmpty()) {
            return new ResponseDto(ERROR, String.format("%s is empty -- there is nothing in it to read.", ContentTypeUtil.fileNameOf(key)));
        }
        int cap = maxChars == null || maxChars < 1 ? MAX_CHARS : Math.min(maxChars, MAX_CHARS);
        ObjectTextDto out = new ObjectTextDto();
        out.setBucket(bucket); out.setKey(key); out.setName(ContentTypeUtil.fileNameOf(key));
        out.setEtag(metadata.getEtag()); out.setSize(metadata.getSize()); out.setContentType(metadata.getContentType());
        out.setKind(kindOf(key));
        out.setTotalChars(extracted.length());
        out.setTruncated(extracted.length() > cap);
        out.setText(extracted.length() > cap ? extracted.substring(0, cap) : extracted);
        out.setChars(out.getText().length());
        return new ResponseDto(SUCCESS, out.isTruncated()
            ? String.format("Read %s: the first %,d of %,d characters.", out.getName(), out.getChars(), out.getTotalChars())
            : String.format("Read %s: %,d characters.", out.getName(), out.getChars()), out);
    }

    /** How the words came about, so the panel can say "transcript" or "described by the vision model". */
    static String kindOf(String key) {
        switch (ContentTypeUtil.categoryOf(key)) {
            case AUDIO: return "transcript";
            case IMAGE: return "description";
            default: return "text";
        }
    }
}
