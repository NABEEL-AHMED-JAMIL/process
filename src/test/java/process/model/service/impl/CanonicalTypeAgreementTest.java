package process.model.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Invariant I3 (MIG-156): the file-type folding table is the same on both sides of the console boundary.
 *
 * An agent's "Target file types" are compared with a file's extension after folding the spellings of
 * one format together -- jpeg to jpg, tiff to tif, htm to html, yml to yaml, mpeg to mpg. The backend
 * enforces it (FileChatServiceImpl.canonicalType); the console decides which agents to OFFER by its own
 * copy (file-chat.ts canonicalType() over SAME_FORMAT). Verbatim from the Java side: "This side is the
 * one that enforces, so the lists have to agree -- a picker offering a file the server then refuses is
 * worse than not offering it." If they drift, an agent silently stops accepting a file type it
 * accepted yesterday.
 *
 * This spans the backend/frontend boundary the migration is not allowed to move, so it is a golden-file
 * test: both tables are read from SOURCE -- the Java method body and the TypeScript constant, in the
 * same test -- and held to src/test/resources/golden/canonical-type-folding.txt and to each other.
 *
 * The console lives in the sibling checkout ../scheduler1 (override with -Dscheduler1.dir or
 * SCHEDULER1_DIR). Where it is not checked out, the frontend comparison is skipped, loudly; the Java
 * side is always held to the golden file.
 */
class CanonicalTypeAgreementTest {

    private static final Path GOLDEN = Paths.get("src", "test", "resources", "golden", "canonical-type-folding.txt");
    private static final Path JAVA = Paths.get("src", "main", "java", "process", "model", "service", "impl",
        "FileChatServiceImpl.java");
    private static final String TS = "next/src/app/features/objects/chat/file-chat.ts";

    private static Map<String, String> golden() throws Exception {
        Map<String, String> pairs = new TreeMap<>();
        for (String line : Files.readAllLines(GOLDEN, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                String[] pair = trimmed.split("=", 2);
                pairs.put(pair[0].trim(), pair[1].trim());
            }
        }
        return pairs;
    }

    /** The pairs as the Java method spells them: if ("x".equals(lower)) { return "y"; }. */
    private static Map<String, String> javaTable() throws Exception {
        String source = new String(Files.readAllBytes(JAVA), StandardCharsets.UTF_8);
        int start = source.indexOf("private static String canonicalType(String type)");
        assertThat(start).as("FileChatServiceImpl.canonicalType").isNotNegative();
        String body = source.substring(start, source.indexOf("\n    }\n", start));
        Map<String, String> pairs = new TreeMap<>();
        Matcher pair = Pattern.compile("if \\(\"(\\w+)\"\\.equals\\(lower\\)\\)\\s*\\{\\s*return \"(\\w+)\";").matcher(body);
        while (pair.find()) {
            pairs.put(pair.group(1), pair.group(2));
        }
        assertThat(body.split("\\bif \\(", -1).length - 1)
            .as("every branch of canonicalType is a folding pair this test can read").isEqualTo(pairs.size());
        return pairs;
    }

    /** The pairs as the TypeScript constant spells them: const SAME_FORMAT = { x: 'y', ... }. */
    private static Map<String, String> typeScriptTable(Path file) throws Exception {
        String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Matcher constant = Pattern.compile("const SAME_FORMAT\\s*:\\s*Record<string,\\s*string>\\s*=\\s*\\{([^}]*)\\}")
            .matcher(source);
        assertThat(constant.find()).as("SAME_FORMAT in %s", file).isTrue();
        Map<String, String> pairs = new TreeMap<>();
        Matcher pair = Pattern.compile("['\"]?(\\w+)['\"]?\\s*:\\s*['\"](\\w+)['\"]").matcher(constant.group(1));
        while (pair.find()) {
            pairs.put(pair.group(1), pair.group(2));
        }
        return pairs;
    }

    private static Path console() {
        String configured = System.getProperty("scheduler1.dir", System.getenv("SCHEDULER1_DIR"));
        return Paths.get(configured == null ? "../scheduler1" : configured).resolve(TS);
    }

    @Test
    void theBackendFoldsExactlyTheGoldenPairs() throws Exception {
        assertThat(javaTable()).isEqualTo(golden());
    }

    @Test
    void theConsoleFoldsExactlyWhatTheBackendFolds() throws Exception {
        Path file = console();
        assumeTrue(Files.exists(file), "the console is not checked out at " + file.toAbsolutePath()
            + " -- the frontend half of I3 did not run");

        Map<String, String> console = typeScriptTable(file);
        assertThat(console).as("file-chat.ts SAME_FORMAT against FileChatServiceImpl.canonicalType").isEqualTo(javaTable());
        assertThat(console).as("file-chat.ts SAME_FORMAT against the golden file").isEqualTo(golden());
    }

    /** And the method does what its table says, after trimming and lower-casing, passing others through. */
    @Test
    void theBackendAppliesItsTableCaseAndSpaceBlind() throws Exception {
        Method method = FileChatServiceImpl.class.getDeclaredMethod("canonicalType", String.class);
        method.setAccessible(true);
        for (Map.Entry<String, String> pair : golden().entrySet()) {
            assertThat(method.invoke(null, " " + pair.getKey().toUpperCase() + " ")).isEqualTo(pair.getValue());
            assertThat(method.invoke(null, pair.getValue())).isEqualTo(pair.getValue());
        }
        assertThat(method.invoke(null, "CSV")).isEqualTo("csv");
        assertThat(method.invoke(null, (Object) null)).isEqualTo("");
    }
}
