package process.billing;

import org.barco.platform.meter.Meter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-80/182: usage is reported against platform-commons' one Meter vocabulary, through its one
 * reporter. A local copy of either is how the keys drifted apart (billing-client knew 16 of 18).
 * process's MeterClient stays for the meter's reads (Cost &amp; usage, the rate card) until Billing
 * owns them; it sends nothing to /v1/events itself.
 */
class OneMeterVocabularyTest {

    private static final Pattern LOCAL_COPY = Pattern.compile(
        "\\b(enum\\s+Meter|class\\s+UsageEvent)|/v1/events\\b|\"(" + Arrays.stream(Meter.values())
            .map(meter -> Pattern.quote(meter.key())).collect(Collectors.joining("|")) + ")\"");

    @Test
    void noSourceKeepsItsOwnMeterKeysOrClient() throws IOException {
        List<String> copies;
        try (Stream<Path> files = Files.walk(Paths.get("src", "main", "java"))) {
            copies = files.filter(f -> f.toString().endsWith(".java")).filter(f -> LOCAL_COPY.matcher(read(f)).find())
                .map(Path::toString).collect(Collectors.toList());
        }
        assertThat(copies).isEmpty();
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
