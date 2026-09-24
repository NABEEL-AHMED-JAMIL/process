package process.directory;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.identity.IdentityPort;
import process.time.TimestamptzRehearsal;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-166 / MIG-153: V160 and V161 on a full copy of the live etl_job, then both audits run against it for real,
 * with Identity's answers read from identity_db (read-only). Not part of `mvn test`:
 * scripts/rehearse-identity-directory.sh makes the copy, runs this and drops it.
 */
class DirectoryRehearsal {

    private static final String SIX = "('tenant', 'app_user', 'tenant_request', 'page_access_profile', 'page_access_profile_page', "
        + "'user_page_access')";

    @Test
    void aFullCopyTakesV160AndV161AndIsAudited() throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        String copy = System.getenv("DIRECTORY_REHEARSAL_DB");
        String identityDb = System.getenv("DIRECTORY_REHEARSAL_IDENTITY_DB");
        assumeTrue(server != null && copy != null && identityDb != null, "the rehearsal databases are not named");
        assertThat(copy).as("a copy, never the live database").isNotEqualTo("etl_job").matches("[a-z0-9_]+");
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + copy + "$1"));
            pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
            pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
            pool.setMaximumPoolSize(2);
            JdbcTemplate sql = new JdbcTemplate(pool);

            long ontoIdentity = sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND confrelid::regclass::text IN "
                + SIX + " AND conrelid::regclass::text NOT IN " + SIX, Long.class);
            long allOthers = sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f'", Long.class) - ontoIdentity;
            System.out.println("REHEARSAL live copy before: " + ontoIdentity + " foreign keys onto Identity's six tables, "
                + allOthers + " others");
            List<String> had = sql.queryForList("SELECT id FROM databasechangelog", String.class);

            TimestamptzRehearsal.liquibase(pool, null);

            List<String> ran = sql.queryForList("SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
            ran.removeAll(had);
            assertThat(ran).containsExactly("160.0-user-directory", "161.0-demote-identity-foreign-keys");
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' AND confrelid::regclass::text IN "
                + SIX + " AND conrelid::regclass::text NOT IN " + SIX, Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f'", Long.class)).isEqualTo(allOthers);
            assertThat(sql.queryForObject("SELECT count(*) FROM user_directory", Long.class)).isZero();

            IdentityPort identity = fromIdentityDb(server, identityDb);
            long activeBefore = sql.queryForObject("SELECT count(*) FROM source_job WHERE job_status = 'Active'", Long.class);
            TenantOrphanAudit.Report tenants = new TenantOrphanAudit(sql, identity, new WorkspaceRetirement(sql)).run();
            assertThat(tenants.isChecked()).isTrue();
            System.out.println("REHEARSAL tenant audit: " + tenants.getTables().size() + " Core tables audited " + tenants.getTables());
            System.out.println("REHEARSAL tenant audit: orphans " + tenants.getOrphans());
            System.out.println("REHEARSAL tenant audit: deleted workspaces whose active jobs were retired " + tenants.getRetired()
                + " (active jobs " + activeBefore + " -> "
                + sql.queryForObject("SELECT count(*) FROM source_job WHERE job_status = 'Active'", Long.class) + ")");

            UserDirectory directory = new UserDirectory(sql, Runnable::run);
            UserDirectoryReconciliation reconciliation = new UserDirectoryReconciliation(sql, identity, directory);
            List<String[]> columns = reconciliation.authorColumns();
            List<String> names = new ArrayList<>();
            columns.forEach(c -> names.add(c[0] + "." + c[1]));
            System.out.println("REHEARSAL audit-stamp columns in Core's own tables: " + columns.size() + " " + names);
            UserDirectoryReconciliation.Report people = reconciliation.run();
            assertThat(people.isChecked()).isTrue();
            System.out.println("REHEARSAL user reconciliation: backfilled " + people.getBackfilled().size() + ", dangling authors "
                + people.getDanglingAuthors() + ", directory rows now "
                + sql.queryForObject("SELECT count(*) FROM user_directory", Long.class));
            UserDirectoryReconciliation.Report again = reconciliation.run();
            assertThat(again.getBackfilled()).as("a second run finds the directory complete").isEmpty();
            assertThat(again.getRepaired()).isEmpty();
            assertThat(again.getUnknownToIdentity()).isEmpty();
        }
    }

    /** IdentityPort's two directory reads, answered from identity_db in a read-only session. */
    private static IdentityPort fromIdentityDb(String server, String identityDb) {
        String url = server.replaceAll("/[^/?]+(\\?.*)?$", "/" + identityDb + "$1");
        IdentityPort identity = mock(IdentityPort.class);
        when(identity.workspaces(any())).thenAnswer(call -> {
            List<IdentityPort.Workspace> found = new ArrayList<>();
            query(url, "SELECT tenant_id, tenant_name, tenant_code, status FROM tenant WHERE tenant_id = ANY (?)",
                call.getArgument(0), rs -> found.add(new IdentityPort.Workspace(rs.getLong(1), rs.getString(2), rs.getString(3),
                    rs.getString(4))));
            return found;
        });
        when(identity.people(any())).thenAnswer(call -> {
            Map<Long, IdentityPort.Person> found = new HashMap<>();
            query(url, "SELECT app_user_id, tenant_id, username, full_name, user_role, status FROM app_user WHERE app_user_id = ANY (?)",
                call.getArgument(0), rs -> found.put(rs.getLong(1), new IdentityPort.Person(rs.getLong(1), (Long) rs.getObject(2),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))));
            return found;
        });
        return identity;
    }

    private interface Row {
        void read(ResultSet rs) throws Exception;
    }

    private static void query(String url, String sql, Collection<Long> ids, Row row) throws Exception {
        try (Connection c = DriverManager.getConnection(url, System.getenv("NOTIFICATIONS_TEST_DB_USER"),
            System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"))) {
            c.setReadOnly(true);
            try (PreparedStatement select = c.prepareStatement(sql)) {
                select.setArray(1, c.createArrayOf("bigint", ids.toArray()));
                try (ResultSet rs = select.executeQuery()) {
                    while (rs.next()) row.read(rs);
                }
            }
        }
    }
}
