package process.schema;

import com.zaxxer.hikari.HikariDataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-177 (P6): what etl_job IS, from its changelog alone -- the only definition a moved table can be
 * rebuilt from. Opt-in like EtlJobChangelogPostgresTest (NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD).
 *
 * With PROVENANCE_REFERENCE_DB set (a long-lived database, e.g. dev etl_job) it also diffs the two,
 * column by column, and prints the differences: run it before every table move.
 */
class SchemaProvenancePostgresTest {

    private static String server;
    private static String scratch;
    private static HikariDataSource fresh;

    @BeforeAll
    static void buildFromTheChangelog() throws Exception {
        server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        assumeTrue(server != null, "NOTIFICATIONS_TEST_DB_URL is not set");
        scratch = "etl_job_provenance_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = admin(server); Statement sql = admin.createStatement()) {
            sql.execute("CREATE DATABASE " + scratch);
        }
        fresh = pool(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + scratch + "$1"));
        SpringLiquibase liquibase = new SpringLiquibase();
        liquibase.setDataSource(fresh);
        liquibase.setChangeLog("classpath:db/changelog/db.changelog-master.yaml");
        liquibase.setContexts("init");
        liquibase.setResourceLoader(new DefaultResourceLoader(SchemaProvenancePostgresTest.class.getClassLoader()));
        liquibase.afterPropertiesSet();
    }

    @AfterAll
    static void dropIt() throws Exception {
        if (fresh == null) return;
        fresh.close();
        try (Connection admin = admin(server); Statement sql = admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS " + scratch);
        }
    }

    private static Connection admin(String url) throws Exception {
        return DriverManager.getConnection(url, System.getenv("NOTIFICATIONS_TEST_DB_USER"), System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
    }

    private static HikariDataSource pool(String url) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl(url);
        pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
        pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
        pool.setMaximumPoolSize(2);
        return pool;
    }

    private static Set<String> sequences(JdbcTemplate sql) {
        return new TreeSet<>(sql.queryForList("SELECT sequencename FROM pg_sequences WHERE schemaname = 'public'", String.class));
    }

    private static List<String> matches(Pattern pattern, Path root) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                Matcher m = pattern.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(m.group(1));
                }
            }
        }
        return found;
    }

    /**
     * Every sequence an entity draws from, and every one code names in a literal nextval(), exists in
     * a database built from the changelog alone. A literal nextval fails at run time, not build time,
     * so a renamed or re-homed sequence would only show up on the first insert (JobAuditLogRepository).
     */
    @Test
    void everySequenceTheCodeDrawsFromIsBuiltByTheChangelog() throws IOException {
        Path code = Paths.get("src/main/java");
        List<String> named = new ArrayList<>();
        named.addAll(matches(Pattern.compile("name\\s*=\\s*\"sequence_name\"\\s*,\\s*value\\s*=\\s*\"([^\"]+)\""), code));
        named.addAll(matches(Pattern.compile("sequenceName\\s*=\\s*\"([^\"]+)\""), code));
        named.addAll(matches(Pattern.compile("nextval\\('([A-Za-z0-9_]+)'\\)"), code));
        assertThat(named).as("the scan sees the code").contains("job_audit_logs_source_seq");

        Set<String> built = sequences(new JdbcTemplate(fresh));
        List<String> missing = named.stream().filter(n -> !built.contains(n)).distinct().collect(Collectors.toList());
        assertThat(missing).isEmpty();
    }

    /** The column-by-column diff against a long-lived database, printed; opt-in by PROVENANCE_REFERENCE_DB. */
    @Test
    void theChangelogBuildsWhatTheLongLivedDatabaseHas() {
        String reference = System.getenv("PROVENANCE_REFERENCE_DB");
        assumeTrue(reference != null, "PROVENANCE_REFERENCE_DB is not set");
        String shape = "SELECT c.table_name || '.' || c.column_name || ' ' || c.data_type || coalesce('(' || c.character_maximum_length || ')', '') "
            + "|| CASE WHEN c.is_nullable = 'NO' THEN ' not null' ELSE '' END FROM information_schema.columns c "
            + "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
            + "WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'";
        // Indexes and constraints by definition, not by name: a name Postgres generated differs between
        // two databases built the same way, and is not a difference in shape.
        String indexes = "SELECT 'index ' || tablename || ' ' || regexp_replace(indexdef, '^CREATE (UNIQUE )?INDEX \\S+ ON ', 'CREATE \\1INDEX ON ') "
            + "FROM pg_indexes WHERE schemaname = 'public'";
        String constraints = "SELECT 'constraint ' || conrelid::regclass || ' ' || pg_get_constraintdef(oid) FROM pg_constraint "
            + "WHERE connamespace = 'public'::regnamespace AND contype IN ('p', 'u', 'f', 'c')";
        Set<String> built = new TreeSet<>();
        Set<String> lived = new TreeSet<>();
        for (String query : new String[] {shape, indexes, constraints}) {
            built.addAll(new JdbcTemplate(fresh).queryForList(query, String.class));
        }
        try (HikariDataSource ref = pool(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + reference + "$1"))) {
            for (String query : new String[] {shape, indexes, constraints}) {
                lived.addAll(new JdbcTemplate(ref).queryForList(query, String.class));
            }
        }
        Set<String> onlyBuilt = new TreeSet<>(built);
        onlyBuilt.removeAll(lived);
        Set<String> onlyLived = new TreeSet<>(lived);
        onlyLived.removeAll(built);
        System.out.println("PROVENANCE only-in-changelog " + onlyBuilt.size());
        onlyBuilt.forEach(line -> System.out.println("PROVENANCE + " + line));
        System.out.println("PROVENANCE only-in-" + reference + " " + onlyLived.size());
        onlyLived.forEach(line -> System.out.println("PROVENANCE - " + line));
    }
}
