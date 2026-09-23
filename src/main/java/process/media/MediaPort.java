package process.media;

import process.model.dto.ResponseDto;

/**
 * The one door from the rest of process into Media & Documents (MIG-40), as NotificationPort was for
 * Notifications. FileChat and report export ask for text, a conversion or a mailed file through
 * here; nothing outside process.media names Media's classes. When Media & Documents is its own
 * service (MIG-48) the implementation behind this interface changes, not its callers.
 *
 * Media's own REST endpoints are not behind this port -- they are Media, and move with it.
 *
 * @author Nabeel Ahmed
 */
public interface MediaPort {

    /** A file's text, from the extraction cache when it has it. Null when there is none to read. */
    String extractText(String bucket, String key, String etag) throws UnreadableFileException, Exception;

    /**
     * The same extraction, told which Ollama model should look at the pixels of a scan and what it
     * should look for. Null or blank means the configured default -- exactly the three-argument call.
     */
    String extractText(String bucket, String key, String etag, String visionModel, String visionInstructions)
        throws UnreadableFileException, Exception;

    /** One document format to another (LibreOffice). */
    byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception;

    /** Drops a cached extraction, when the object behind it has changed. */
    void forgetExtraction(String bucket, String key, String etag);

    /** Emails a file the caller generated (FileChat's export), under file share's ceilings and template. */
    ResponseDto emailGeneratedFile(String recipientEmail, String itemName, String filename, String contentType,
        byte[] bytes, String message) throws Exception;
}
