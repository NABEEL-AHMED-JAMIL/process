package process.model.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.projection.SourceTaskTypeProjection;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kafka connections screen asks, per profile, which topics publish through it
 * (SourceTaskTypeRepository.fetchTopicsForProfile, a native query). The platform's own profile has
 * no workspace, so its tenantId is null -- and Hibernate binds an untyped null as bytea, which
 * Postgres refuses to compare with a bigint column ("operator does not exist: bigint = bytea"). The
 * screen got a 500 for the one profile every workspace without its own now resolves to (MIG-45).
 *
 * The real repository method, through Hibernate, over a throwaway Postgres.
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class TopicsForProfilePostgresTest {

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("topics_profile");
        jpa = new ScratchJpa(db);
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM source_task_type WHERE source_task_type_id BETWEEN 97001 AND 97099");
        this.sql.update("DELETE FROM kafka_connection_profile WHERE kafka_connection_profile_id BETWEEN 9701 AND 9799");
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9998, 'Active', 'TOPICS', 'Topics') "
            + "ON CONFLICT DO NOTHING");
    }

    private void profile(long id, Long tenantId, boolean isDefault) {
        this.sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, bootstrap_servers, is_default, "
            + "profile_name, security_protocol, status, tenant_id) VALUES (?, 'kafka:9092', ?, ?, 'PLAINTEXT', 'Active', ?)",
            id, isDefault, "profile-" + id, tenantId);
    }

    private void topic(long id, String name, Long profileId) {
        this.sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, "
            + "task_type_status, kafka_connection_profile_id, tenant_id) VALUES (?, ?, 'fixture', ?, 'Active', ?, 9998)",
            id, name, "topic=" + name + "&partitions=[*]", profileId);
    }

    private List<String> topicsFor(long profileId, boolean includeUnrouted, Long tenantId) {
        SourceTaskTypeRepository repository = jpa.repository(SourceTaskTypeRepository.class);
        return jpa.transactions().execute(status -> repository.fetchTopicsForProfile(profileId, includeUnrouted, tenantId))
            .stream().map(SourceTaskTypeProjection::getServiceName).collect(Collectors.toList());
    }

    @Test
    void thePlatformProfileWithNoWorkspaceListsItsTopics() {
        // Not the default: the database already seeds the one platform default (V70.2), and for a
        // profile with no workspace the service passes includeUnrouted=false either way.
        this.profile(9701, null, false);
        this.topic(97001, "platform-routed", 9701L);
        this.topic(97002, "unrouted", null);

        // Exactly the call SettingServiceImpl makes for a profile with no workspace.
        assertThat(this.topicsFor(9701, false, null)).containsExactly("platform-routed");
    }

    @Test
    void aWorkspaceDefaultAlsoListsTheWorkspacesUnroutedTopics() {
        this.profile(9702, 9998L, true);
        this.topic(97003, "routed-here", 9702L);
        this.topic(97004, "unrouted-here", null);

        assertThat(this.topicsFor(9702, true, 9998L)).containsExactlyInAnyOrder("routed-here", "unrouted-here");
    }
}
