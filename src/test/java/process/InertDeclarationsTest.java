package process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-58 keep-or-drop: nothing in Core runs asynchronously and nothing receives STOMP messages, and that stays a decision.
 * @EnableAsync was inert (no @Async method existed) and is dropped: making a send or publish asynchronous changes the
 * ordering callers rely on, so it must come back on purpose, with this test changed in the same commit. The inbound
 * STOMP prefix is already gone; no @MessageMapping may appear without a design for it.
 */
class InertDeclarationsTest {

    /** An annotation in use: at the start of a line (after indentation), not a mention in a comment. */
    private static final Pattern ASYNC = Pattern.compile("(?m)^\\s*@(Async|EnableAsync)\\b");
    private static final Pattern STOMP_IN = Pattern.compile("(?m)^\\s*@MessageMapping\\b|setApplicationDestinationPrefixes");

    private static List<String> matching(Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                if (pattern.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).find()) {
                    found.add(file.toString());
                }
            }
        }
        return found;
    }

    @Test
    void nothingRunsAsynchronously() throws IOException {
        assertThat(matching(ASYNC)).as("@Async / @EnableAsync in Core: a behaviour change, decide it on purpose").isEmpty();
    }

    @Test
    void nothingReceivesStompMessages() throws IOException {
        assertThat(matching(STOMP_IN)).as("inbound STOMP in Core").isEmpty();
    }
}
