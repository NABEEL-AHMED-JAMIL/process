package process.media;

import org.springframework.stereotype.Component;
import process.media.extraction.ExtractionService;
import process.media.share.FileShareService;
import process.model.dto.ResponseDto;

/**
 * MediaPort while Media & Documents still lives in this process: delegation, nothing more.
 *
 * @author Nabeel Ahmed
 */
@Component
public class InProcessMedia implements MediaPort {

    private final ExtractionService extraction;
    private final FileShareService share;

    public InProcessMedia(ExtractionService extraction, FileShareService share) {
        this.extraction = extraction;
        this.share = share;
    }

    @Override
    public String extractText(String bucket, String key, String etag) throws Exception {
        return this.extraction.extractText(bucket, key, etag);
    }

    @Override
    public String extractText(String bucket, String key, String etag, String visionModel, String visionInstructions)
        throws Exception {
        return this.extraction.extractText(bucket, key, etag, visionModel, visionInstructions);
    }

    @Override
    public byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception {
        return this.extraction.convertContent(content, sourceExtension, targetExtension);
    }

    @Override
    public void forgetExtraction(String bucket, String key, String etag) {
        this.extraction.forgetExtraction(bucket, key, etag);
    }

    @Override
    public ResponseDto emailGeneratedFile(String recipientEmail, String itemName, String filename, String contentType,
        byte[] bytes, String message) throws Exception {
        return this.share.emailGeneratedFile(recipientEmail, itemName, filename, contentType, bytes, message);
    }
}
