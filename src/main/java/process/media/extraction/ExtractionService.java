package process.media.extraction;

import process.media.MediaPort;
/**
 * Text extraction and format conversion -- Media & Documents (MIG-40). Formerly
 * FileChatExtractionService: FileChat is its biggest caller, not its owner; preview, object-as-text
 * and report export use it too. Callers outside Media go through MediaPort.
 */
public interface ExtractionService {

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

}
