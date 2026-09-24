package process.time;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-28 / MIG-163 (ADR-002): process does not tell the JVM what zone it lives in, and does not ask it.
 *
 * TimeZone.setDefault("America/Chicago") in ModelApplication.main was the only thing that made etl_job's
 * naive timestamps mean Chicago; any process that started without it -- a test, a tool, the next service --
 * was five or six hours wrong with no error. ADR-002 rejected pinning the default everywhere as the fix. So
 * the call is gone, nothing pins it in its place (a -Duser.timezone, a TZ in the image), and no code reads the
 * JVM's default zone to turn a time into a wall-clock reading or back: the business zone is BusinessTime.ZONE,
 * named where it is meant.
 */
class ModelApplicationTimeZoneTest {

    private static final Path MAIN = Paths.get("src/main/java");

    /** Each a way to read, or set, the JVM's default zone. */
    private static final Pattern DEFAULT_ZONE = Pattern.compile(
        "TimeZone\\.setDefault|user\\.timezone|ZoneId\\.systemDefault\\(\\)|TimeZone\\.getDefault\\(\\)\\.(?!getID)"
            + "|LocalDateTime\\.now\\(\\)|LocalDate\\.now\\(\\)|LocalTime\\.now\\(\\)|ZonedDateTime\\.now\\(\\)"
            + "|Timestamp\\.valueOf\\(|\\.toLocalDateTime\\(\\)|new SimpleDateFormat\\(");

    private static List<String> matches(Path root, Pattern pattern) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().collect(Collectors.toList())) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                        continue;
                    }
                    Matcher m = pattern.matcher(line);
                    if (m.find()) {
                        found.add(root.relativize(file) + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        return found;
    }

    @Test
    void theApplicationNoLongerSetsTheJvmsZone() throws IOException {
        String main = new String(Files.readAllBytes(MAIN.resolve("process/ModelApplication.java")), StandardCharsets.UTF_8);
        assertThat(main.replaceAll("(?s)/\\*.*?\\*/", "")).doesNotContain("setDefault");
    }

    @Test
    void nothingPinsTheZoneInItsPlace() throws IOException {
        for (String file : new String[] {"Dockerfile", "docker-compose.yml"}) {
            String text = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
            assertThat(text).as(file).doesNotContain("user.timezone").doesNotContainPattern("(?m)^\\s*-?\\s*TZ[=:]");
        }
    }

    @Test
    void noCodeReadsTheJvmsDefaultZone() throws IOException {
        assertThat(matches(MAIN, DEFAULT_ZONE)).as("reads of the JVM's default zone; use BusinessTime").isEmpty();
    }
}
