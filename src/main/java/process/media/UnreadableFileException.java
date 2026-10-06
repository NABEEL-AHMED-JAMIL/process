package process.media;

/**
 * Thrown when the file was read far enough to say exactly WHY nothing usable came out of it:
 * a password-protected PDF, a scan whose page image no vision model would describe.
 *
 * It exists because the alternative -- returning that explanation as the file's text -- was
 * a real bug rather than a stylistic choice. A sentence returned as content is not null, so
 * it was cached for seven days as the file's text and chunked and embedded into the RAG
 * index as though it were the document, and every later question about that file was
 * answered against the apology instead of the file. Returning null instead is honest about
 * the content but silent about the cause: the reader was told only "couldn't get any
 * readable content out of this .pdf file", which reads as "this file type isn't supported"
 * for a file whose type is supported perfectly well and which happens to be encrypted, or
 * whose vision model is simply down for the minute.
 *
 * {@code getMessage()} is written for the person in the chat panel and is safe to show
 * verbatim -- it names no bucket, key, endpoint or stack detail.
 */
public class UnreadableFileException extends Exception {

    public UnreadableFileException(String message) {
        super(message);
    }
}
