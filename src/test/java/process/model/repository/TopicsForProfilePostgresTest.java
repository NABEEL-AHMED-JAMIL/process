package process.model.repository;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.projection.SourceTaskTypeProjection;
import process.model.projection.TopicOptionProjection;

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
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9997, 'Active', 'OWNKAFKA', 'Own Kafka') "
            + "ON CONFLICT DO NOTHING");
    }

    private void profile(long id, Long tenantId, boolean isDefault) {
        this.sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, bootstrap_servers, is_default, "
            + "profile_name, security_protocol, status, tenant_id) VALUES (?, 'kafka:9092', ?, ?, 'PLAINTEXT', 'Active', ?)",
            id, isDefault, "profile-" + id, tenantId);
    }

    private void topic(long id, String name, Long profileId) {
        this.topic(id, name, profileId, 9998L);
    }

    private void topic(long id, String name, Long profileId, long tenantId) {
        this.sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, "
            + "task_type_status, kafka_connection_profile_id, tenant_id) VALUES (?, ?, 'fixture', ?, 'Active', ?, ?)",
            id, name, "topic=" + name + "&partitions=[*]", profileId, tenantId);
    }

    private void inactiveProfile(long id, long tenantId) {
        this.sql.update("INSERT INTO kafka_connection_profile (kafka_connection_profile_id, bootstrap_servers, is_default, "
            + "profile_name, security_protocol, status, tenant_id) VALUES (?, 'kafka:9092', false, ?, 'PLAINTEXT', 'Inactive', ?)",
            id, "profile-" + id, tenantId);
    }

    private List<String> platformDefaultTopics(long profileId, boolean allTenants, Long tenantId) {
        SourceTaskTypeRepository repository = jpa.repository(SourceTaskTypeRepository.class);
        return jpa.transactions().execute(status -> repository.fetchTopicsForPlatformDefault(profileId, allTenants, tenantId))
            .stream().map(SourceTaskTypeProjection::getServiceName).collect(Collectors.toList());
    }

    private List<String> platformDefaultOptions(long profileId, boolean allTenants, Long tenantId) {
        SourceTaskTypeRepository repository = jpa.repository(SourceTaskTypeRepository.class);
        return jpa.transactions().execute(status -> repository.fetchTopicOptionsForPlatformDefault(profileId, allTenants, tenantId))
            .stream().map(TopicOptionProjection::getServiceName).collect(Collectors.toList());
    }

    /**
     * Workspace 9998 has no Kafka profile, so its unrouted topic goes to the platform default;
     * workspace 9997 has one of its own (inactive, which still counts -- the resolver refuses it
     * rather than borrowing the platform's), so its unrouted topic goes nowhere near it.
     */
    private void platformDefaultFixture() {
        this.profile(9703, null, false);
        this.inactiveProfile(9704, 9997L);
        this.topic(97011, "pf-unrouted-no-kafka", null, 9998L);
        this.topic(97012, "pf-named-no-kafka", 9703L, 9998L);
        this.topic(97013, "pf-unrouted-own-kafka", null, 9997L);
        this.topic(97014, "pf-named-own-kafka", 9703L, 9997L);
        this.topic(97015, "pf-on-own-profile", 9704L, 9997L);
    }

    @Test
    void thePlatformDefaultCarriesTheUnroutedTopicsOfAWorkspaceWithNoKafka() {
        this.platformDefaultFixture();

        // A tenant caller: its own workspace only, the unrouted topic and the one naming the profile.
        assertThat(this.platformDefaultTopics(9703, false, 9998L))
            .containsExactlyInAnyOrder("pf-unrouted-no-kafka", "pf-named-no-kafka");
        assertThat(this.platformDefaultOptions(9703, false, 9998L))
            .containsExactlyInAnyOrder("pf-unrouted-no-kafka", "pf-named-no-kafka");
    }

    @Test
    void aPlatformAdminSeesThePlatformDefaultsTopicsInEveryWorkspaceWithNoKafka() {
        this.platformDefaultFixture();

        // A null tenant id binds as bytea unless the query casts it through text.
        List<String> topics = this.platformDefaultTopics(9703, true, null);
        assertThat(topics).contains("pf-unrouted-no-kafka", "pf-named-no-kafka", "pf-named-own-kafka");
        assertThat(topics).doesNotContain("pf-unrouted-own-kafka", "pf-on-own-profile");

        List<String> options = this.platformDefaultOptions(9703, true, null);
        assertThat(options).contains("pf-unrouted-no-kafka", "pf-named-no-kafka", "pf-named-own-kafka");
        assertThat(options).doesNotContain("pf-unrouted-own-kafka", "pf-on-own-profile");
    }

    @Test
    void aWorkspaceWithKafkaOfItsOwnSeesOnlyWhatNamesThePlatformProfile() {
        this.platformDefaultFixture();

        assertThat(this.platformDefaultTopics(9703, false, 9997L)).containsExactly("pf-named-own-kafka");
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
