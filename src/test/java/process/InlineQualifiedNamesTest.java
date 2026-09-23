package process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Classes are named by import, not inline by their full package. An inline name hides a dependency
 * from the import block -- where boundary tests read them: in process, two MIG-53 dependencies that
 * broke the Analytics boundary were invisible to AnalyticsBoundaryTest until the inline names became
 * imports. A name kept qualified on purpose (two classes share a simple name in one file) is listed in
 * src/test/resources/inline-fqn-allowlist.txt.
 */
class InlineQualifiedNamesTest {

    private static final Pattern QUALIFIED = Pattern.compile(
        "(?<![\\w.])((?:java|javax|jakarta|org|com|io|software|reactor|okhttp3|net|lombok|process|liquibase|ch|kotlin)"
            + "(?:\\.[a-z_][a-z0-9_]*)+)\\.([A-Z][A-Za-z0-9_]*)");

    @Test
    void noClassIsNamedInlineByItsPackage() throws IOException {
        Set<String> allowed = new HashSet<>();
        Path list = Paths.get("src", "test", "resources", "inline-fqn-allowlist.txt");
        if (Files.exists(list)) {
            for (String line : Files.readAllLines(list, StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    allowed.add(line.trim());
                }
            }
        }
        List<String> found = new ArrayList<>();
        for (String root : new String[] {"src/main/java", "src/test/java"}) {
            Path base = Paths.get(root);
            if (!Files.isDirectory(base)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> walk = Files.walk(base)) {
                files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
            }
            for (Path file : files) {
                String code = codeOnly(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                String relative = file.toString().replace('\\', '/');
                for (String line : code.split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("import ") || trimmed.startsWith("package ")) {
                        continue;
                    }
                    Matcher m = QUALIFIED.matcher(line);
                    while (m.find()) {
                        String name = m.group(1) + "." + m.group(2);
                        if (!allowed.contains(relative + "|" + name)) {
                            found.add(relative + ": " + name);
                        }
                    }
                }
            }
        }
        assertThat(found).as("import these instead of naming them inline").isEmpty();
    }

    /** The source with comments, string and char literals and text blocks blanked, lines kept. */
    static String codeOnly(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            int end;
            if (src.startsWith("//", i)) {
                end = src.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (src.startsWith("/*", i)) {
                end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                end = src.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                char quote = src.charAt(i);
                end = i + 1;
                while (end < n && src.charAt(end) != quote) {
                    end += src.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, n);
            } else {
                out.append(src.charAt(i++));
                continue;
            }
            for (int k = i; k < end; k++) {
                out.append(src.charAt(k) == '\n' ? '\n' : ' ');
            }
            i = end;
        }
        return out.toString();
    }
}
