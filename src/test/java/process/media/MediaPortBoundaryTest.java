package process.media;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Media & Documents carved behind MediaPort inside process (MIG-40), as MIG-20 did for
 * Notifications: everything Media does lives under process.media, the rest of process reaches it
 * only through the port, and Media reaches the rest of process only through the edges it will
 * have as a service -- Storage's guarded byte access, the Notifications port, the meter, tenant
 * security and shared DTOs/utilities. When Media leaves (MIG-48) only the port's implementation
 * changes.
 */
class MediaPortBoundaryTest {

    private static final Path MAIN = Paths.get("src", "main", "java", "process");
    private static final Path MEDIA = MAIN.resolve("media");
    private static final Pattern IMPORT = Pattern.compile("^import (?:static )?process\\.([A-Za-z0-9_.]+);", Pattern.MULTILINE);

    /** What the rest of process may name: the port and what crosses it. */
    private static final List<String> THE_PORT = Arrays.asList("media.MediaPort", "media.UnreadableFileException");

    /** What Media may use outside itself: the edges it keeps as a service. */
    private static final List<String> MEDIA_EDGES = Arrays.asList(
        "model.service.StorageBrowserService", "notifications.", "billing.", "security.",
        "model.dto.", "model.enums.", "util.");

    private static List<Path> sources(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    @Test
    void mediaLivesUnderItsOwnPackage() {
        for (String moved : new String[] {"model/service/impl/ObjectPreviewServiceImpl.java", "model/service/impl/ObjectTextServiceImpl.java",
            "model/service/ExtractionService.java", "model/service/impl/ExtractionServiceImpl.java",
            "model/service/DocumentConverterService.java", "model/service/AudioTranscriptService.java",
            "model/service/FileShareService.java", "util/TextCleanerUtil.java", "api/TextCleanerRestApi.java",
            "api/DocumentConverterRestApi.java", "api/AudioTranscriptRestApi.java", "api/FileShareRestApi.java"}) {
            assertThat(MAIN.resolve(moved)).as(moved).doesNotExist();
        }
        assertThat(MEDIA.resolve("MediaPort.java")).exists();
    }

    @Test
    void theRestOfProcessReachesMediaOnlyThroughThePort() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : sources(MAIN)) {
            if (file.startsWith(MEDIA)) continue;
            Matcher m = IMPORT.matcher(new String(Files.readAllBytes(file)));
            while (m.find()) {
                String imported = m.group(1);
                if (imported.startsWith("media.") && !THE_PORT.contains(imported)) {
                    offenders.add(MAIN.relativize(file) + " imports process." + imported);
                }
            }
        }
        assertThat(offenders).as("reaching past MediaPort").isEmpty();
    }

    @Test
    void mediaReachesTheRestOfProcessOnlyThroughItsServiceEdges() throws IOException {
        assertThat(MEDIA).exists();
        List<String> offenders = new ArrayList<>();
        for (Path file : sources(MEDIA)) {
            Matcher m = IMPORT.matcher(new String(Files.readAllBytes(file)));
            while (m.find()) {
                String imported = m.group(1);
                if (imported.startsWith("media.")) continue;
                if (MEDIA_EDGES.stream().noneMatch(imported::startsWith)) {
                    offenders.add(MEDIA.relativize(file) + " imports process." + imported);
                }
            }
        }
        assertThat(offenders).as("Media reaching into Core").isEmpty();
    }
}
