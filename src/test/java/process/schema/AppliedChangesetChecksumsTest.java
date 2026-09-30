package process.schema;

import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every changeset etl_job has already run still has the checksum it ran with (MIG-204).
 *
 * Liquibase validates the checksum of each applied changeset at every start-up and refuses to start on a
 * mismatch; a changeset it cannot match by file, id and author it runs again. Either would take a deployed
 * database down, and both can come from a Liquibase upgrade alone (the parent moved it from 3.8.9 to 4.9.1)
 * as much as from editing an old changeset. The recorded values are the live database's
 * (applied-changesets-etl_job.txt); the ones computed here are whatever the Liquibase on the classpath makes
 * of today's changelog. No database: the changelog is parsed as SpringLiquibase would parse it, with the
 * parameters the deployment passes (spring.liquibase.parameters.*: kafka:9092 over PLAINTEXT).
 *
 * Rows from files the master changelog no longer includes (V1-V49, archived behind the V50 baseline) are
 * not run and not validated, so they are only counted, not compared.
 */
class AppliedChangesetChecksumsTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String APPLIED = "/db/applied-changesets-etl_job.txt";

    @Test
    void everyAppliedChangesetStillHasTheChecksumItRanWith() throws Exception {
        Map<String, String> applied = applied();
        DatabaseChangeLog changelog = parse();

        Set<String> includedFiles = new HashSet<>();
        Map<String, String> computed = new LinkedHashMap<>();
        for (ChangeSet changeSet : changelog.getChangeSets()) {
            String file = normalize(changeSet.getFilePath());
            includedFiles.add(file);
            computed.put(key(changeSet.getId(), changeSet.getAuthor(), file), changeSet.generateCheckSum().toString());
        }

        List<String> mismatched = new ArrayList<>();
        List<String> lost = new ArrayList<>();
        int compared = 0;
        for (Map.Entry<String, String> row : applied.entrySet()) {
            String file = row.getKey().split("\\|")[2];
            if (!includedFiles.contains(file)) {
                continue;
            }
            String now = computed.get(row.getKey());
            if (now == null) {
                lost.add(row.getKey());
            } else if (!now.equals(row.getValue())) {
                mismatched.add(row.getKey() + " ran as " + row.getValue() + ", is now " + now);
            } else {
                compared++;
            }
        }

        assertThat(mismatched).as("applied changesets whose checksum changed").isEmpty();
        assertThat(lost).as("applied changesets no longer found by file, id and author: they would run again").isEmpty();
        // 70 on 2026-09-29; a floor so a parse that finds nothing cannot pass by comparing nothing.
        assertThat(compared).isGreaterThanOrEqualTo(70);
    }

    private static DatabaseChangeLog parse() throws Exception {
        ResourceAccessor resources = new ClassLoaderResourceAccessor(AppliedChangesetChecksumsTest.class.getClassLoader());
        ChangeLogParameters parameters = new ChangeLogParameters();
        parameters.set("platformKafkaBootstrapServers", "kafka:9092");
        parameters.set("platformKafkaSecurityProtocol", "PLAINTEXT");
        return ChangeLogParserFactory.getInstance().getParser(CHANGELOG, resources)
            .parse(CHANGELOG, parameters, resources);
    }

    /** id|author|filename -> md5sum, from the recorded databasechangelog. */
    private static Map<String, String> applied() throws Exception {
        Map<String, String> rows = new LinkedHashMap<>();
        try (InputStream in = AppliedChangesetChecksumsTest.class.getResourceAsStream(APPLIED)) {
            assertThat(in).as(APPLIED).isNotNull();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("\\|", -1);
                rows.put(key(fields[0], fields[1], fields[2]), fields[3]);
            }
        }
        assertThat(rows).isNotEmpty();
        return rows;
    }

    /** As Liquibase 4's DatabaseChangeLog.normalizePath, which 3.x does not expose: how it matches a file to a row. */
    private static String normalize(String path) {
        return path.replaceFirst("^classpath:", "").replace('\\', '/').replaceAll("//+", "/").replaceFirst("^/", "");
    }

    private static String key(String id, String author, String file) {
        return id + "|" + author + "|" + file;
    }
}
