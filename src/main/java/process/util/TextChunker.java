package process.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits extracted document text into overlapping chunks for embedding and retrieval.
 *
 * Character-based rather than token-based, deliberately: a token count needs the target model's
 * own tokenizer to be exact, and this chunker's output crosses providers (whatever agent the
 * caller picked) -- an approximate, provider-agnostic size that errs a little small costs
 * nothing, where an exact count for the wrong tokenizer would be actively misleading.
 *
 * @author Nabeel Ahmed
 * */
public final class TextChunker {

    /**
     * Character size per chunk. ~1000 chars is roughly 200-250 tokens for English prose --
     * small enough that a chunk is topically coherent (one embedding vector represents one
     * idea, not three unrelated ones stitched together), large enough that most chunks don't
     * split a sentence mid-thought.
     */
    public static final int DEFAULT_CHUNK_SIZE = 1000;

    /**
     * Overlap between consecutive chunks. Without it, a sentence straddling the boundary between
     * chunk N and chunk N+1 is whole in neither -- its embedding represents half an idea, and a
     * query that should retrieve it may match neither half well enough to rank in the top K.
     */
    public static final int DEFAULT_OVERLAP = 150;

    private TextChunker() {}

    public static List<String> chunk(String text) {
        return chunk(text, DEFAULT_CHUNK_SIZE, DEFAULT_OVERLAP);
    }

    /**
     * @param chunkSize target size in characters; the last chunk may be shorter
     * @param overlap   how many trailing characters of one chunk reappear at the start of the next
     */
    public static List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive.");
        }
        if (overlap < 0 || overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap must be >= 0 and less than chunkSize.");
        }
        String trimmed = text.trim();
        int step = chunkSize - overlap;
        int position = 0;
        while (position < trimmed.length()) {
            int end = Math.min(position + chunkSize, trimmed.length());
            // Prefer breaking on whitespace near the boundary, so a chunk does not end mid-word --
            // cosmetic for reading, but it also keeps a word's embedding from being split across
            // two chunks with neither carrying the whole token. Only look back a short distance;
            // a chunk with no whitespace in its last 100 chars (a long URL, a hash) breaks as-is
            // rather than being stretched arbitrarily far to find one.
            if (end < trimmed.length()) {
                int lookback = Math.max(position, end - 100);
                int lastSpace = trimmed.lastIndexOf(' ', end);
                if (lastSpace >= lookback) {
                    end = lastSpace;
                }
            }
            String piece = trimmed.substring(position, end).trim();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= trimmed.length()) {
                break;
            }
            int next = end - overlap;
            // Guard against a pathological input (e.g. all whitespace near the boundary) where
            // trimming the overlap back would not actually advance -- without this an unlucky
            // text shape could loop forever instead of terminating.
            position = next > position ? next : end;
        }
        return chunks;
    }
}
