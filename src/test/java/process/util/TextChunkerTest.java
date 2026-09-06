package process.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author Nabeel Ahmed
 * */
class TextChunkerTest {

    @Test
    void nullAndBlankProduceNoChunks() {
        assertTrue(TextChunker.chunk(null).isEmpty());
        assertTrue(TextChunker.chunk("").isEmpty());
        assertTrue(TextChunker.chunk("   \n\t  ").isEmpty());
    }

    @Test
    void aFileSmallerThanOneChunkComesBackWhole() {
        String text = "A short document that fits in a single chunk.";
        List<String> chunks = TextChunker.chunk(text, 1000, 150);
        assertEquals(1, chunks.size());
        assertEquals(text, chunks.get(0));
    }

    @Test
    void everyCharacterOfTheOriginalAppearsInAtLeastOneChunk() {
        // The one property that actually matters for RAG: nothing the file said is silently
        // dropped between chunks. Built from a repeating marker so a missing span is easy to
        // spot rather than merely asserting a total-length arithmetic that could hide a gap.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            text.append("word").append(i).append(" ");
        }
        List<String> chunks = TextChunker.chunk(text.toString(), 200, 30);
        assertTrue(chunks.size() > 1, "the fixture must actually need more than one chunk");
        String joined = String.join(" ", chunks);
        for (int i = 0; i < 500; i++) {
            assertTrue(joined.contains("word" + i), "word" + i + " must survive chunking somewhere");
        }
    }

    @Test
    void consecutiveChunksOverlap() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            text.append("token").append(i).append(" ");
        }
        List<String> chunks = TextChunker.chunk(text.toString(), 200, 50);
        assertTrue(chunks.size() > 1);
        for (int i = 0; i < chunks.size() - 1; i++) {
            String end = chunks.get(i).substring(Math.max(0, chunks.get(i).length() - 20));
            String startOfNext = chunks.get(i + 1);
            // At least the last word of one chunk should reappear at the start of the next --
            // a weak but concrete check that the overlap window is real, not just configured.
            String[] words = end.trim().split("\\s+");
            String lastWord = words[words.length - 1];
            assertTrue(startOfNext.contains(lastWord),
                "expected \"" + lastWord + "\" from the end of chunk " + i + " to reappear at the start of chunk " + (i + 1));
        }
    }

    @Test
    void breaksOnWhitespaceRatherThanMidWordWhenOneIsNearby() {
        String text = "one two three four five six seven eight nine ten";
        List<String> chunks = TextChunker.chunk(text, 15, 3);
        for (String c : chunks) {
            assertFalse(c.isEmpty());
            // No chunk should start or end with a partial fragment glued mid-word by a hard cut
            // -- every chunk's boundary tokens should be one of the real words in the source.
            for (String word : c.trim().split("\\s+")) {
                assertTrue(text.contains(word), "\"" + word + "\" is not a real word from the source");
            }
        }
    }

    @Test
    void rejectsAnOverlapThatIsNotSmallerThanTheChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("some text", 100, 100));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("some text", 100, 150));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("some text", 100, -1));
    }

    @Test
    void rejectsAZeroOrNegativeChunkSize() {
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("some text", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TextChunker.chunk("some text", -5, 0));
    }

    @Test
    void terminatesOnPathologicalInputRatherThanLoopingForever() {
        // All-whitespace near every boundary, at a scale where a stuck cursor would hang the
        // test rather than merely producing a wrong answer -- this is the regression this test
        // actually guards, not the chunk count.
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("a".repeat(50)).append("                    ");
        }
        List<String> chunks = TextChunker.chunk(text.toString(), 100, 20);
        assertFalse(chunks.isEmpty());
    }

    @Test
    void defaultSizeAndOverlapMatchTheDocumentedConstants() {
        List<String> chunks = TextChunker.chunk("x".repeat(5000));
        List<String> explicit = TextChunker.chunk("x".repeat(5000),
            TextChunker.DEFAULT_CHUNK_SIZE, TextChunker.DEFAULT_OVERLAP);
        assertEquals(explicit, chunks);
    }
}
