package process.schema;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-14 against a real Postgres: app_user.token_version (V66) and the queries that move and read it.
 * TokenRevocationAcrossInstancesTest runs the same queries against a map; this proves the SQL, and
 * that saving an entity loaded before a bump cannot put the old version back. Opt-in (IdentityPostgres).
 */
class IdentityTokenVersionPostgresTest {

    @Test
    void theVersionStartsAtZeroMovesOnlyByTheBumpAndSurvivesAStaleSave() throws Exception {
        try (IdentityPostgres db = IdentityPostgres.create("mig14_version").migrate().withJpa()) {
            JdbcTemplate sql = db.sql();
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (5001, 'Active', 'a', 'a')");
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (5002, 'Active', 'b', 'b')");
            for (long[] row : new long[][] {{9001, 5001}, {9002, 5001}, {9003, 5002}}) {
                sql.update("INSERT INTO app_user (app_user_id, tenant_id, full_name, password, status, user_role, username) "
                    + "VALUES (?, ?, 'x', 'x', 'Active', 'TENANT_USER', ?)", row[0], row[1], "u" + row[0] + "@x.example");
            }
            assertThat(sql.queryForObject("SELECT is_nullable || ':' || column_default FROM information_schema.columns "
                + "WHERE table_name = 'app_user' AND column_name = 'token_version'", String.class)).isEqualTo("NO:0");
            AppUserRepository users = db.repository(AppUserRepository.class);

            AppUser stale = db.transaction().execute(tx -> users.findById(9001L).get());
            assertThat(stale.getTokenVersion()).isZero();

            // Spring Data's repository proxy supplies the @Transactional the interface asks for; a bare factory does not.
            db.transaction().execute(tx -> users.bumpTokenVersion(9001L));
            assertThat(users.findTokenVersion(9001L)).isEqualTo(1);

            stale.setFullName("Saved after the bump");
            db.transaction().executeWithoutResult(tx -> users.save(stale));
            assertThat(users.findTokenVersion(9001L)).as("a stale entity does not write the version back").isEqualTo(1);
            Integer reloaded = db.transaction().execute(tx -> users.findById(9001L).get().getTokenVersion());
            assertThat(reloaded).isEqualTo(1);

            Integer bumped = db.transaction().execute(tx -> users.bumpTokenVersionsInTenant(5001L));
            assertThat(bumped).isEqualTo(2);
            assertThat(users.findTokenVersion(9001L)).isEqualTo(2);
            assertThat(users.findTokenVersion(9002L)).isEqualTo(1);
            assertThat(users.findTokenVersion(9003L)).as("another tenant is untouched").isZero();
            List<Long> inA = users.findIdsInTenant(5001L).stream().map(Number::longValue).sorted().collect(Collectors.toList());
            assertThat(inA).containsExactly(9001L, 9002L);
            assertThat(users.findTokenVersion(404L)).isNull();
        }
    }
}
