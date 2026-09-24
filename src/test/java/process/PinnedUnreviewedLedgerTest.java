package process;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The @Tag("pinned-unreviewed") convention (MIG-154), and its count as the measure of progress.
 *
 * A pinning test records what the code does today with NO claim that it is right: it exists
 * because nobody can say whether the behaviour was designed, and guessing either way is a defect.
 * So every one of them is waiting on a decision, and the number of them still waiting is the
 * visible evidence that the undocumented surface is being decided rather than carried. The
 * testing strategy (16, sections 2.5 and 10.2 gate D11) puts it this way: no new pinned test
 * without a decision item, and the total must not rise.
 *
 * That is enforced here, against src/test/resources/pinned-unreviewed.txt:
 *
 *  - every tagged test method has a ledger line, and every ledger line names a tagged method, so
 *    a pin cannot be added or resolved without the ledger -- and so the pull request -- showing it;
 *  - every ledger line names the board item holding the decision (DEC-nn, or the MIG-nnn row
 *    whose Notes describe the behaviour until a DEC row exists);
 *  - the ledger's "# count:" line equals the number of pins, so any change to the total is a
 *    one-line diff a reviewer cannot miss;
 *  - the tag goes on methods, never on a class, so the count is a count of behaviours.
 *
 * The count is printed on every build. To run the pins alone: mvn -o test -Dgroups=pinned-unreviewed
 *
 * A pinning test that FAILS is not automatically a regression. It is a prompt: resolve the
 * decision, then either fix the code or update the test, with the decision id in the commit.
 */
class PinnedUnreviewedLedgerTest {

    static final String TAG = "pinned-unreviewed";

    private static final Path LEDGER = Paths.get("src", "test", "resources", "pinned-unreviewed.txt");
    /** @Tag("pinned-unreviewed"), however it is spelled: qualified or not, with or without value =. */
    private static final Pattern TAG_AT = Pattern.compile(
        "@(?:[\\w.]+\\.)?Tag\\(\\s*(?:value\\s*=\\s*)?\"" + Pattern.quote(TAG) + "\"\\s*\\)");
    private static final Pattern NEXT_DECLARATION = Pattern.compile(
        "\\b(?:class|interface|enum)\\s+(\\w+)|\\bvoid\\s+(\\w+)\\s*\\(");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern DECISION = Pattern.compile("^(?:DEC|MIG)-\\d+$");
    private static final Pattern COUNT_LINE = Pattern.compile("^#\\s*count:\\s*(\\d+)\\s*$");

    @Test
    void everyPinIsInTheLedgerWithTheDecisionItIsWaitingOn() throws IOException {
        List<String> classLevel = new ArrayList<>();
        TreeSet<String> tagged = taggedMethods(classLevel);
        Ledger ledger = Ledger.read();

        assertThat(classLevel)
            .as("tag test METHODS, not classes, so the count is a count of behaviours")
            .isEmpty();
        assertThat(ledger.problems)
            .as("each ledger line is '<class>#<method> | <DEC-nn or MIG-nnn> | <what is pinned>'")
            .isEmpty();
        assertThat(ledger.entries.keySet())
            .as("a @Tag(\"%s\") test needs a ledger line naming its decision item, and a resolved "
                + "pin takes its line with it", TAG)
            .containsExactlyElementsOf(tagged);
        assertThat(ledger.recordedCount)
            .as("the ledger's '# count:' line must equal the number of pins -- change it in the "
                + "same commit, so the pull request shows the delta")
            .isEqualTo(tagged.size());

        System.out.println("pinned-unreviewed: " + tagged.size());
        ledger.entries.forEach((test, decision) -> System.out.println("  " + decision + "  " + test));
    }

    /** The ledger's own grammar, checked separately so a typo reads as a typo. */
    @Test
    void theLedgerNamesADecisionOnEveryLine() throws IOException {
        Ledger ledger = Ledger.read();
        assertThat(ledger.recordedCount).as("the ledger must carry a '# count: N' line").isNotNull();
        ledger.entries.values().forEach(decision -> assertThat(decision).matches(DECISION.pattern()));
    }

    /** Every tagged method as package.Class#method, read from source with comments ignored. */
    private static TreeSet<String> taggedMethods(List<String> classLevel) throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(Paths.get("src", "test", "java"))) {
            files = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path file : files) {
            String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            // Same length as the source, comments and literals blanked: a mention of the tag in a
            // Javadoc is not a tag, and positions still line up with the original.
            String code = InlineQualifiedNamesTest.codeOnly(source);
            Matcher annotation = TAG_AT.matcher(source);
            while (annotation.find()) {
                if (code.charAt(annotation.start()) != '@') {
                    continue;
                }
                Matcher declaration = NEXT_DECLARATION.matcher(code);
                if (!declaration.find(annotation.end())) {
                    continue;
                }
                String owner = qualifiedName(source, file);
                if (declaration.group(1) != null) {
                    classLevel.add(owner);
                } else {
                    found.add(owner + "#" + declaration.group(2));
                }
            }
        }
        return found;
    }

    private static String qualifiedName(String source, Path file) {
        Matcher pkg = PACKAGE.matcher(source);
        String simple = file.getFileName().toString().replaceAll("\\.java$", "");
        return pkg.find() ? pkg.group(1) + "." + simple : simple;
    }

    private static final class Ledger {
        final Map<String, String> entries = new TreeMap<>();
        final List<String> problems = new ArrayList<>();
        Integer recordedCount;

        static Ledger read() throws IOException {
            Ledger ledger = new Ledger();
            for (String raw : Files.readAllLines(LEDGER, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                Matcher count = COUNT_LINE.matcher(line);
                if (count.matches()) {
                    ledger.recordedCount = Integer.valueOf(count.group(1));
                    continue;
                }
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                if (parts.length < 3 || parts[2].trim().isEmpty() || !parts[0].trim().contains("#")
                    || !DECISION.matcher(parts[1].trim()).matches()) {
                    ledger.problems.add(line);
                    continue;
                }
                if (ledger.entries.put(parts[0].trim(), parts[1].trim()) != null) {
                    ledger.problems.add("listed twice: " + line);
                }
            }
            return ledger;
        }
    }
}
