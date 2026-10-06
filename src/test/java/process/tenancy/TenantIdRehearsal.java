package process.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.time.TimestamptzRehearsal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * MIG-29 / MIG-164: V102 on a full copy of a long-lived etl_job -- the reconciliation the rows ask for, on real data.
 *
 * Like TimestamptzRehearsal, not part of `mvn test`: scripts/rehearse-timestamptz.sh makes the copy, runs both, and
 * drops it. Row counts per table before; after V102 the same counts, no NULL tenant_id, and no row whose tenant_id
 * differs from the one its parent JOIN derives. Then the rollback, and the columns gone.
 */
class TenantIdRehearsal {

    @Test
    void aFullCopyIsBackfilledByJoinWithNothingLeftNullAndNothingDisagreeing() throws Exception {
        String server = System.getenv("NOTIFICATIONS_TEST_DB_URL");
        String copy = System.getenv("TIMESTAMPTZ_REHEARSAL_DB");
        assumeTrue(server != null && copy != null, "NOTIFICATIONS_TEST_DB_URL and TIMESTAMPTZ_REHEARSAL_DB are not set");
        assertThat(copy).as("a copy, never the live database").isNotEqualTo("etl_job").matches("[a-z0-9_]+");
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.setJdbcUrl(server.replaceAll("/[^/?]+(\\?.*)?$", "/" + copy + "$1"));
            pool.setUsername(System.getenv("NOTIFICATIONS_TEST_DB_USER"));
            pool.setPassword(System.getenv("NOTIFICATIONS_TEST_DB_PASSWORD"));
            pool.setMaximumPoolSize(2);
            JdbcTemplate sql = new JdbcTemplate(pool);

            Map<String, Long> before = new LinkedHashMap<>();
            for (String table : TenantIdOnChildTablesPostgresTest.DERIVED.keySet()) {
                before.put(table, sql.queryForObject("SELECT count(*) FROM " + table, Long.class));
            }
            List<String> had = sql.queryForList("SELECT id FROM databasechangelog", String.class);
            TimestamptzRehearsal.liquibase(pool, null);
            List<String> ran = sql.queryForList("SELECT id FROM databasechangelog ORDER BY orderexecuted", String.class);
            ran.removeAll(had);
            assertThat(ran).contains(TenantIdOnChildTablesPostgresTest.V102);

            Map<String, Long> after = new LinkedHashMap<>();
            Map<String, Long> perTenant = new LinkedHashMap<>();
            for (Map.Entry<String, String> table : TenantIdOnChildTablesPostgresTest.DERIVED.entrySet()) {
                after.put(table.getKey(), sql.queryForObject("SELECT count(*) FROM " + table.getKey(), Long.class));
                assertThat(sql.queryForObject("SELECT count(*) FROM " + table.getKey() + " WHERE tenant_id IS NULL", Long.class))
                    .as(table.getKey()).isZero();
                assertThat(sql.queryForObject("SELECT count(*) FROM (" + table.getValue() + ") r WHERE r.tenant_id IS DISTINCT FROM r.parent",
                    Long.class)).as(table.getKey()).isZero();
                perTenant.put(table.getKey(), sql.queryForObject("SELECT count(DISTINCT tenant_id) FROM " + table.getKey(), Long.class));
            }
            System.out.println("REHEARSAL V102 rows " + after + ", tenants per table " + perTenant);
            assertThat(after).isEqualTo(before);

            TimestamptzRehearsal.liquibase(pool, ran.size());
            for (String table : TenantIdOnChildTablesPostgresTest.DERIVED.keySet()) {
                assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id'",
                    Long.class, table)).as(table).isZero();
            }
        }
    }
}
