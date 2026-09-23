package process.analytics;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which packages process.analytics is allowed to reach, and which may reach it.
 *
 * <b>Document 15's "verify dependency boundaries", which was PARTIAL for a specific reason: the
 * module had grown a dependency on process.model.service that its author flagged and nobody
 * reviewed.</b> Reviewing it once would have left it in the same state a week later. This states
 * the boundary and fails when it moves.
 *
 * <b>What the review found.</b> There IS a package-level cycle -- analytics reaches
 * process.model.service and process.model.repository, and model.service.impl reaches back into
 * analytics -- and it is deliberate rather than accidental:
 *
 *   analytics -> model.repository   DatasetResolver reads storage_connection to resolve an alias,
 *                                   and the benchmark writes its results. Analytics has no store
 *                                   of its own and should not grow one.
 *   analytics -> model.service      Two calls, both to StorageBrowserService, and both about
 *                                   OBJECTS rather than about rows: does this key already exist
 *                                   (the write-back's refuse-to-overwrite), and list what is
 *                                   there. Reimplementing either inside analytics would be a
 *                                   second storage client with a second set of credentials.
 *   analytics -> config          The query-completed announcement resolves a broker profile and
 *                                   borrows the platform's KafkaTemplate. Same shape as the two
 *                                   above: a TRANSPORT rather than a data store, already
 *                                   configured once for the whole application, and a second
 *                                   producer inside analytics would be a second set of broker
 *                                   credentials to keep in step.
 *   model.service -> analytics      AnalyticsDatasetServiceImpl uses DatasetResolver to check a
 *                                   dataset is reachable before registering it. Resolution is
 *                                   analytics's own rule -- ownership, status, provider -- and
 *                                   duplicating it is how the two would disagree about who may
 *                                   read what.
 *
 * The cycle is therefore accepted, NARROWLY: exactly the types listed below, and nothing else.
 * What this test stops is the next dependency being added without anyone noticing -- which is how
 * a narrow, argued cycle becomes a broad, unexamined one.
 *
 * A source-text check rather than a bytecode one, because it needs no new dependency and reads
 * imports, which is where a boundary is actually crossed.
 *
 * @author Nabeel Ahmed
 */
public class AnalyticsBoundaryTest {

    private static final File ANALYTICS =
        new File("src/main/java/process/analytics");
    private static final File PROCESS =
        new File("src/main/java/process");

    /**
     * The only types outside its own package tree that analytics may import from process.*.
     *
     * Adding to this list is a decision. The test's whole value is that it has to be made
     * deliberately, in a diff, rather than by an import statement nobody reads.
     */
    private static final Set<String> ANALYTICS_MAY_IMPORT = new LinkedHashSet<String>(Arrays.asList(
        "process.model.service.StorageBrowserService",
        "process.model.repository.BenchmarkResultRepository",
        "process.config.KafkaConnectionResolver",
        "process.config.KafkaTemplateProvider",
        // The same question asked of storage-service once it owns the connections (MIG-68): DuckDB's
        // connection vended by Storage, which applies the Active and isOwnedByCaller rules itself.
        "process.storage.remote.RemoteStorageDirectory"));

    /** The only packages outside api/ and engine/ that may reach INTO analytics. */
    private static final Set<String> MAY_REACH_IN = new LinkedHashSet<String>(Arrays.asList(
        "process.model.service.impl.AnalyticsDatasetServiceImpl",
        // The advice that maps an escaped AnalyticsException to the module's own refusal shape --
        // HTTP 200 carrying status ERROR -- rather than to a 500. It imports the exception TYPE
        // and nothing else: no service, no engine, no dataset. An exception a module throws
        // across its own front door is part of that door's vocabulary, the same way the enums and
        // DTOs excluded above are, and the alternative is 24 controllers each repeating the same
        // catch with nothing to catch the twenty-fifth.
        "process.config.GlobalExceptionHandler"));

    private static List<File> javaFilesUnder(File root) {
        List<File> found = new ArrayList<File>();
        File[] children = root.listFiles();
        if (children == null) {
            return found;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                found.addAll(javaFilesUnder(child));
            } else if (child.getName().endsWith(".java")) {
                found.add(child);
            }
        }
        return found;
    }

    private static List<String> importsOf(File file) throws IOException {
        List<String> imports = new ArrayList<String>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                imports.add(trimmed.substring("import ".length()).replace(";", "").trim());
            } else if (trimmed.startsWith("public ") || trimmed.startsWith("class ")) {
                break;
            }
        }
        return imports;
    }

    /** The fully-qualified name a file declares, taken from its path under src/main/java. */
    private static String className(File file) {
        String path = file.getPath().replace(File.separatorChar, '/');
        int root = path.indexOf("src/main/java/");
        String owned = root < 0 ? path : path.substring(root + "src/main/java/".length());
        return owned.replace(".java", "").replace('/', '.');
    }

    @Test
    void analyticsReachesOnlyTheTypesItsBoundaryAllows() throws Exception {
        List<String> crossings = new ArrayList<String>();
        for (File file : javaFilesUnder(ANALYTICS)) {
            for (String imported : importsOf(file)) {
                if (!imported.startsWith("process.")) {
                    continue;
                }
                if (imported.startsWith("process.analytics.")
                    || imported.startsWith("process.util.")
                    || imported.startsWith("process.security.")
                    || imported.startsWith("process.model.enums.")
                    || imported.startsWith("process.model.pojo.")
                    || imported.startsWith("process.model.dto.")) {
                    // Types, enums and helpers: shared vocabulary rather than a collaborator.
                    continue;
                }
                if (!ANALYTICS_MAY_IMPORT.contains(imported)) {
                    crossings.add(file.getName() + " -> " + imported);
                }
            }
        }
        assertThat(crossings)
            .as("analytics grew a dependency nobody argued for; add it to ANALYTICS_MAY_IMPORT "
                + "with a reason, or move the call")
            .isEmpty();
    }

    @Test
    void onlyTheControllersAndOneServiceReachIntoAnalytics() throws Exception {
        List<String> crossings = new ArrayList<String>();
        for (File file : javaFilesUnder(PROCESS)) {
            String path = file.getPath().replace('/', '.');
            if (path.contains(".analytics.") || path.contains(".api.")
                || path.contains(".engine.")) {
                // Controllers are the module's front door, and the cron reads its limits.
                continue;
            }
            String owner = className(file);
            if (MAY_REACH_IN.contains(owner)) {
                continue;
            }
            for (String imported : importsOf(file)) {
                if (imported.startsWith("process.analytics")) {
                    crossings.add(owner + " -> " + imported);
                }
            }
        }
        assertThat(crossings)
            .as("something outside the controllers reached into analytics; that is how a narrow, "
                + "argued cycle becomes a broad, unexamined one")
            .isEmpty();
    }

    @Test
    void theBoundaryListIsNotEmpty() {
        // A walk that found no files would make both tests above vacuously green.
        assertThat(javaFilesUnder(ANALYTICS)).hasSizeGreaterThan(10);
        assertThat(javaFilesUnder(PROCESS)).hasSizeGreaterThan(50);
    }
}
