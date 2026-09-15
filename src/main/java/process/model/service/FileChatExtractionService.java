package process.model.service;

/**
 * @author Nabeel Ahmed
 * */
public interface FileChatExtractionService {

    String extractText(String bucket, String key, String etag) throws Exception;

    /**
     * The same extraction, told which model should look at the pixels and what it should be
     * looking for when the file turns out to have no text in it.
     *
     * Only the vision fallback uses these two. A file with a text layer, an audio file or a
     * gzipped log extracts to exactly the same text for every agent, and stays on the
     * agent-independent cache entry it always had; a scan does not -- agent 1022 "Vision
     * Assistant" runs gemma3:4b against pages of medical-imaging instructions, and what it gets
     * back is not what a general-purpose agent running the default llava:7b with the built-in
     * prompt would get back. Before this existed both of those agents were served whichever
     * description happened to be produced first, from a cache entry keyed on bucket:key:etag
     * alone, and the agent's own configuration had no effect whatsoever on what was actually
     * read out of the file.
     *
     * A null or blank model falls back to the configured default, and null or blank instructions
     * fall back to the built-in describe-and-transcribe prompt -- so passing nothing is exactly
     * the three-argument call.
     *
     * NOTE for the caller: the model named here is posted to the LOCAL Ollama endpoint. Pass an
     * agent's model only when that agent actually runs against Ollama; a hosted provider's model
     * name (gpt-4o, claude-*) means nothing to Ollama and would turn every scan into a vision
     * failure. Pass null for those and let the configured local default do the looking.
     */
    String extractText(String bucket, String key, String etag,
        String visionModel, String visionInstructions) throws Exception;

    byte[] convertContent(byte[] content, String sourceExtension, String targetExtension) throws Exception;


    public void forgetExtraction(String bucket, String key, String etag);

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
    class UnreadableFileException extends Exception {

        public UnreadableFileException(String message) {
            super(message);
        }
    }
}
