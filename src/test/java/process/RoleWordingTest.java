package process;

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
 * A message a person reads names a role in words: "platform administrator", "tenant administrator",
 * "tenant user" -- lowercase mid-sentence, and never the clipped "admin" or the enum name. Only string
 * literals are read; log lines are for operators and are left alone.
 */
class RoleWordingTest {

    private static final Pattern CLIPPED = Pattern.compile("(?i)\\b(platform|tenant)[ _-]admins?\\b");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern LOG = Pattern.compile("\\b(log|logger|LOG|LOGGER)\\.(trace|debug|info|warn|error)\\(");

    @Test
    void messagesNameRolesInFullWords() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Paths.get("src", "main", "java"))) {
            files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : files) {
            int number = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                number++;
                if (LOG.matcher(line).find()) {
                    continue;
                }
                Matcher literal = LITERAL.matcher(line);
                while (literal.find()) {
                    String text = literal.group(1);
                    // An identifier such as "PLATFORM_ADMIN" or "platform-admin" is a value, not prose.
                    if (text.contains(" ") && CLIPPED.matcher(text).find()) {
                        found.add(file + ":" + number + ": " + text);
                    }
                }
            }
        }
        assertThat(found).as("write \"platform administrator\" / \"tenant administrator\"").isEmpty();
    }
}
