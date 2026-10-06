package process.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-59: the features V26-V30 removed stay removed. No controller answers for them and no code calls them,
 * so a trace found later is plainly residue (the catalogue is in V70.4-retire-task-form-names.yaml).
 *
 * job-search's F76800 task (send_email_batch_f76800.py) still builds a dynamicForm.json/fetchSubmissionByUuid
 * URL; it gets a 404 here, which is the truth -- that belongs to job-search to remove (recorded on MIG-59).
 */
class RemovedFeatureEndpointsTest {

    private static final Pattern REMOVED = Pattern.compile(
        "(?i)dynamicForm|fetchSubmissionByUuid|queryDefinition|querySchedule|queryExecution|databaseConnectionProfile"
            + "|pdfHighlighter|emailReceiver|EMAIL_RECEIVER|PIPELINE_IDS|avatarBackup");

    @Test
    void nothingInProcessServesOrCallsARemovedFeature() throws Exception {
        List<String> found = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(Paths.get("src/main/java"))) {
            sources = walk.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList());
        }
        for (Path source : sources) {
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                // Comments may name what was removed and why; code may not use it.
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) {
                    continue;
                }
                if (REMOVED.matcher(line).find()) {
                    found.add(source + ":" + (i + 1) + ": " + line);
                }
            }
        }
        assertThat(found).isEmpty();
    }
}
