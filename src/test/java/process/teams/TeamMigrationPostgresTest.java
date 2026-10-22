package process.teams;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.identity.IdentityPort;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F4 (MIG-401, owner T-Q1) on Core's schema as the changelog builds it: every Approval request, pipeline definition (its
 * review's reviewers and an Approval step's) and alert rule that names a group (an access profile id) names the team that
 * group became instead -- in its own workspace, the same group id of another workspace becoming that workspace's own team
 * -- so each still goes to the same people; a workspace Identity cannot answer for is left as it was for the next tick;
 * once nothing names a group, Identity is not asked again.
 */
class TeamMigrationPostgresTest {

    static final long A = 8871L;
    static final long B = 8872L;

    static ScratchPostgres db;
    static JdbcTemplate login;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("team_migration");
        login = db.jdbc();
        // The rows of before the migration: V224 took group and recipient_groups out of a fresh database, so they are put
        // back here, and an approval is kept without its run (the run's tables are not what this is about).
        login.execute("ALTER TABLE run_approval DROP CONSTRAINT ck_run_approval_reviewers");
        login.execute("ALTER TABLE run_approval ADD CONSTRAINT ck_run_approval_reviewers CHECK (reviewers_kind IN ('role', 'team', 'group'))");
        login.execute("ALTER TABLE run_approval DROP CONSTRAINT fk_run_approval_run_tenant");
        login.execute("ALTER TABLE run_approval DROP CONSTRAINT fk_run_approval_step");
        login.execute("ALTER TABLE alert_rule ADD COLUMN recipient_groups character varying(512)");
        for (long t : new long[] {A, B}) {
            login.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", t, "t" + t, "T" + t);
            login.update("INSERT INTO pipeline (pipeline_id, pipeline_key, pipeline_name, status, date_created, tenant_id) "
                + "VALUES (?, ?, 'Wound photos (synthetic)', 'Active', now(), ?)", "wounds-" + t, t, t);
            login.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) VALUES (?, ?, 1, ?::json, now())",
                t, t, "{\"version\":1,\"steps\":[{\"key\":\"ask\",\"type\":\"approval\",\"config\":{\"reviewers\":{\"kind\":\"group\","
                    + "\"value\":\"1063\"}}}],\"settings\":{\"review\":{\"required\":[\"internal\"],\"reviewers\":{\"kind\":\"group\",\"value\":\"1064\"}}},"
                    + "\"note\":{\"group\":\"night shift\"}}");
            login.update("INSERT INTO run_approval (tenant_id, job_queue_id, attempt, step_execution_id, step_index, step_key, title, reviewers_kind, "
                + "reviewers_value, expires_at) VALUES (?, ?, 1, ?, 0, 'ask', 'Approve wound sizes', 'group', '1063', now() + interval '1 day')",
                t, 900_000 + t, 900_000 + t);
            login.update("INSERT INTO alert_rule (tenant_id, kind, name, recipient_roles, recipient_groups) VALUES (?, 'RUN_FAILED', 'Runs fail', "
                + "'TENANT_ADMIN', '1064')", t);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    static String approval(long tenant) {
        return login.queryForObject("SELECT reviewers_kind || ':' || reviewers_value FROM run_approval WHERE tenant_id = ?", String.class, tenant);
    }

    static String definition(long tenant) {
        return login.queryForObject("SELECT definition::text FROM pipeline_definition WHERE tenant_id = ?", String.class, tenant);
    }

    static String alertTeams(long tenant) {
        return login.queryForObject("SELECT coalesce(recipient_groups, '-') || '|' || coalesce(recipient_teams, '-') FROM alert_rule "
            + "WHERE tenant_id = ?", String.class, tenant);
    }

    @Test
    void everyGroupBecomesItsWorkspacesTeamOnceAndAWorkspaceIdentityCannotAnswerForWaits() {
        IdentityPort identity = mock(IdentityPort.class);
        Set<Long> both = new LinkedHashSet<>(Arrays.asList(1063L, 1064L));
        when(identity.teamsOfGroups(eq(A), anyCollection())).thenReturn(Map.of(1063L, 501L, 1064L, 502L));
        when(identity.teamsOfGroups(eq(B), anyCollection())).thenThrow(new IdentityPort.Unavailable("down", null));
        TeamMigration migration = new TeamMigration(db.appJdbc(), identity);

        migration.sweep();
        verify(identity).teamsOfGroups(A, both);
        assertThat(approval(A)).isEqualTo("team:501");
        assertThat(definition(A)).contains("\"config\":{\"reviewers\":{\"kind\":\"team\",\"value\":\"501\"}}")
            .contains("\"reviewers\":{\"kind\":\"team\",\"value\":\"502\"}").as("a field called group is left alone")
            .contains("\"note\":{\"group\":\"night shift\"}").doesNotContain("\"kind\":\"group\"");
        assertThat(alertTeams(A)).isEqualTo("-|502");
        assertThat(approval(B)).as("Identity could not say: workspace B waits").isEqualTo("group:1063");
        assertThat(alertTeams(B)).isEqualTo("1064|-");

        reset(identity);
        when(identity.teamsOfGroups(eq(B), anyCollection())).thenReturn(Map.of(1063L, 601L, 1064L, 602L));
        migration.sweep();
        assertThat(approval(B)).as("the same group id is workspace B's own team").isEqualTo("team:601");
        assertThat(definition(B)).contains("{\"kind\":\"team\",\"value\":\"602\"}");
        assertThat(alertTeams(B)).isEqualTo("-|602");
        verify(identity, never()).teamsOfGroups(eq(A), anyCollection());

        reset(identity);
        migration.sweep();
        migration.sweep();
        verify(identity, never()).teamsOfGroups(anyLong(), anyCollection());
    }
}
