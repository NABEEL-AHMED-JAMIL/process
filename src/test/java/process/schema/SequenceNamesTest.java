package process.schema;

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
 * MIG-177: every sequence name in the code is written exactly as Postgres stores it -- lower case.
 * An entity said job_audit_logs_source_Seq while the SQL said job_audit_logs_source_seq; unquoted,
 * Postgres folds both to one sequence, but anything that quotes identifiers (a migration tool, a
 * dump, hibernate.globally_quoted_identifiers) splits them into two, and ids then collide.
 * SchemaProvenancePostgresTest checks each name exists in a database built from the changelog.
 */
class SequenceNamesTest {

    private static final Pattern NAMES = Pattern.compile(
        "name\\s*=\\s*\"sequence_name\"\\s*,\\s*value\\s*=\\s*\"([^\"]+)\"|sequenceName\\s*=\\s*\"([^\"]+)\"|nextval\\('([^']+)'\\)");

    @Test
    void everySequenceNameInTheCodeIsLowerCase() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                Matcher m = NAMES.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    String name = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
                    names.add(file.getFileName() + ": " + name);
                }
            }
        }
        assertThat(names).as("the scan sees the code").contains("JobAuditLogRepository.java: job_audit_logs_source_seq");
        assertThat(names.stream().filter(n -> !n.substring(n.indexOf(": ") + 2).equals(n.substring(n.indexOf(": ") + 2).toLowerCase()))
            .collect(Collectors.toList())).isEmpty();
    }

    /** The audit-log upsert names its sequence in SQL; it must be the entity's own. */
    @Test
    void theAuditLogUpsertDrawsFromTheEntitysSequence() throws IOException {
        String entity = new String(Files.readAllBytes(Paths.get("src/main/java/process/model/pojo/JobAuditLogs.java")), StandardCharsets.UTF_8);
        String repository = new String(Files.readAllBytes(Paths.get("src/main/java/process/model/repository/JobAuditLogRepository.java")), StandardCharsets.UTF_8);
        Matcher declared = Pattern.compile("value\\s*=\\s*\"(job_audit_logs[^\"]*)\"").matcher(entity);
        assertThat(declared.find()).isTrue();
        assertThat(repository).contains("nextval('" + declared.group(1) + "')");
    }
}
