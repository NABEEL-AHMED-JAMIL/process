package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ConfigurationMakerRequest;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.SourceTaskTypeDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.settings.PipelineConfigDto;
import process.settings.TaskReferenceDto;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.B_TYPE;
import static process.tenancy.CoreProbeFixture.A_TYPE;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.B_PROFILE;
import static process.tenancy.CoreProbeFixture.PLATFORM_PROFILE;
import static process.tenancy.CoreProbeFixture.A_PROFILE;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_CONFIG;
import static process.tenancy.CoreProbeFixture.B_SECRET;
import static process.tenancy.CoreProbeFixture.B_HOME;
import static process.tenancy.CoreProbeFixture.B_GROUP;
import static process.tenancy.CoreProbeFixture.B_STORAGE_ALIAS;
import static process.tenancy.CoreProbeFixture.CONFIG_BUCKET;
import static process.tenancy.CoreProbeFixture.B_SECRET_KEY;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.GONE;
import static process.tenancy.CoreProbeFixture.DEFAULT_WS;
import static process.tenancy.CoreProbeFixture.UNKNOWN;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_C;
import static process.tenancy.CoreProbeFixture.C_TYPE;
import static process.tenancy.CoreProbeFixture.C;
import static process.tenancy.CoreProbeFixture.PLATFORM;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;
import process.tenancy.CoreProbeFixture.Caller;

/**
 * MIG-166's CI cross-tenant probe for Core, a workspace's settings: topics and their Kafka routes (/setting.json),
 * configuration values and secrets (/setting.json/pipelineConfig), home pages and task groups
 * (/setting.json/taskReferences), and Kafka connections (/kafkaConnectionProfile.json) -- called by workspace A's
 * tenant admin with B's topic, route, profile, storage alias, key material, configuration entries and references,
 * against a real etl_job (CoreProbeFixture).
 *
 * <b>Two filter conditions, kept apart</b> (MIG-166). kafka_connection_profile keeps the platform's rows, tenant_id
 * NULL, on purpose, and a tenant's relation to them is not "never": a workspace with no connection of its own is
 * shown the platform default (read-only, without where its brokers are or how it logs in) and its runs go through
 * it, so it MAY name it in a route or a new topic -- but may not edit, delete, re-default or test it. B's own
 * profile it may do none of those with. {@link #thePlatformDefaultIsSeenOnlyWithoutOneOfYourOwnAndMayBeUsedButNotChanged}
 * pins both halves: "tenant_id = mine" for what a tenant sees and changes, "tenant_id = mine or NULL" for what it may
 * use. source_task_type has no such second condition any more: its tenant_id is NOT NULL (V39/V50), so the "or
 * tenant_id is null" still written in SourceTaskType's filter and SourceTaskServiceImpl.isSourceTaskTypeVisibleToCaller
 * matches no row -- inert, and left alone.
 *
 * A platform administrator's writes that name a workspace must name a live one: a deleted workspace or an id with no
 * tenant row is refused for topics, configuration and references, end to end over the real tenant table. Opt-in,
 * like every ScratchPostgres test.
 */
class CoreCrossTenantProbeSettingsAndKafkaPostgresTest {

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_settings");
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static SourceTaskTypeDto topic(Long id, String name, Long profile) {
        SourceTaskTypeDto dto = new SourceTaskTypeDto();
        dto.setSourceTaskTypeId(id);
        dto.setServiceName(name);
        dto.setDescription(name);
        dto.setQueueTopicPartition("topic=" + name.toLowerCase().replace(' ', '-') + "&partitions=[*]");
        dto.setKafkaConnectionProfileId(profile);
        return dto;
    }

    private static KafkaConnectionProfileDto profile(Long id, String name) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(id);
        dto.setProfileName(name);
        dto.setBootstrapServers("acme-broker-2.internal:9092");
        dto.setSecurityProtocol("PLAINTEXT");
        return dto;
    }

    private static PipelineConfigDto entry(Long id, Long tenantId, String key, String value) {
        PipelineConfigDto dto = new PipelineConfigDto();
        dto.setId(id);
        dto.setTenantId(tenantId);
        dto.setKey(key);
        dto.setKind("VALUE");
        dto.setValue(value);
        return dto;
    }

    private static TaskReferenceDto reference(Long id, Long tenantId, String kind, String name) {
        TaskReferenceDto dto = new TaskReferenceDto();
        dto.setId(id);
        dto.setTenantId(tenantId);
        dto.setKind(kind);
        dto.setName(name);
        dto.setValue("https://acme.example/" + name.toLowerCase().replace(' ', '-'));
        return dto;
    }

    @Test
    void noSettingsOrKafkaEndpointHandsTheCallerAnotherWorkspacesSettingsOrChangesThem() throws Exception {
        String before = fx.foreignRows();
        long profilesBefore = fx.count("SELECT count(*) FROM kafka_connection_profile");
        long topicsBefore = fx.count("SELECT count(*) FROM source_task_type");

        // ---- /setting.json: topics and routes
        fx.probe("GET setting.json/appSetting", ADMIN_OF_A, fx.settings::appSetting);
        fx.probe("GET setting.json/topics", ADMIN_OF_A, () -> fx.settings.topics(null, 50, null, null));
        fx.probe("GET setting.json/topics(their ids)", ADMIN_OF_A, () -> fx.settings.topics(null, 50, Arrays.asList(B_TYPE, A_TYPE), null));
        assertThat(fx.probe("GET setting.json/topics(their profile)", ADMIN_OF_A, () -> fx.settings.topics(null, 50, null, B_PROFILE)))
            .contains(REFUSED);
        assertThat(fx.probe("GET setting.json/topicsForProfile", ADMIN_OF_A, () -> fx.settings.topicsForProfile(B_PROFILE)))
            .contains(REFUSED);
        // A has a connection of its own, so the platform default is not A's to list topics under.
        assertThat(fx.probe("GET setting.json/topicsForProfile(the platform default)", ADMIN_OF_A,
            () -> fx.settings.topicsForProfile(PLATFORM_PROFILE))).contains(REFUSED);
        // Live broker health follows the same rule: not another workspace's brokers, not a default A does not use.
        assertThat(fx.probe("GET setting.json/profileHealth", ADMIN_OF_A, () -> fx.settings.profileHealth(B_PROFILE, true)))
            .contains(REFUSED);
        assertThat(fx.probe("GET setting.json/profileHealth(the platform default)", ADMIN_OF_A,
            () -> fx.settings.profileHealth(PLATFORM_PROFILE, true))).contains(REFUSED);
        assertThat(fx.probe("PUT setting.json/updateSourceTaskType", ADMIN_OF_A,
            () -> fx.settings.updateSourceTaskType(topic(B_TYPE, "Renamed By Acme", null)))).contains(REFUSED);
        assertThat(fx.probe("PUT setting.json/updateSourceTaskType(my topic onto their profile)", ADMIN_OF_A,
            () -> fx.settings.updateSourceTaskType(topic(A_TYPE, "Acme Topic", B_PROFILE)))).contains(REFUSED);
        assertThat(fx.probe("DELETE setting.json/deleteSourceTaskType", ADMIN_OF_A, () -> fx.settings.deleteSourceTaskType(B_TYPE)))
            .contains(REFUSED);
        assertThat(fx.probe("POST setting.json/addSourceTaskType(their profile)", ADMIN_OF_A,
            () -> fx.settings.addSourceTaskType(topic(null, "Acme On Their Profile", B_PROFILE)))).contains(REFUSED);
        // B has a route for its own topic; A asking for, setting or deleting "its" route for that topic reaches none of B's.
        fx.probe("GET setting.json/fetchKafkaRoute", ADMIN_OF_A, () -> fx.settings.fetchKafkaRoute(B_TYPE));
        assertThat(fx.probe("PUT setting.json/setKafkaRoute(their topic)", ADMIN_OF_A, () -> fx.settings.setKafkaRoute(B_TYPE, A_PROFILE)))
            .contains(REFUSED);
        assertThat(fx.probe("PUT setting.json/setKafkaRoute(their profile)", ADMIN_OF_A, () -> fx.settings.setKafkaRoute(A_TYPE, B_PROFILE)))
            .contains(REFUSED);
        fx.probe("DELETE setting.json/deleteKafkaRoute", ADMIN_OF_A, () -> fx.settings.deleteKafkaRoute(B_TYPE));
        ConfigurationMakerRequest tags = new ConfigurationMakerRequest();
        tags.setXmlTagsInfo(Collections.singletonList(new ConfigurationMakerRequest.TagInfo("bucket", null, "acme-files")));
        fx.probe("POST setting.json/xmlCreateChecker", ADMIN_OF_A, () -> fx.settings.xmlCreateChecker(tags));

        // ---- /setting.json/pipelineConfig and /setting.json/taskReferences: B's entries by id, and lists naming B
        fx.probe("GET setting.json/pipelineConfig", ADMIN_OF_A, () -> fx.pipelineConfig.list(B));
        assertThat(fx.probe("PUT setting.json/pipelineConfig", ADMIN_OF_A,
            () -> fx.pipelineConfig.update(entry(B_CONFIG, B, null, "overwritten by acme")))).contains(REFUSED);
        assertThat(fx.probe("DELETE setting.json/pipelineConfig", ADMIN_OF_A, () -> fx.pipelineConfig.delete(B_CONFIG))).contains(REFUSED);
        assertThat(fx.probe("DELETE setting.json/pipelineConfig(their secret)", ADMIN_OF_A, () -> fx.pipelineConfig.delete(B_SECRET)))
            .contains(REFUSED);
        fx.probe("GET setting.json/taskReferences", ADMIN_OF_A, () -> fx.taskReferences.list("HOME_PAGE", B));
        fx.probe("GET setting.json/taskReferences(groups)", ADMIN_OF_A, () -> fx.taskReferences.list("TASK_GROUP", B));
        assertThat(fx.probe("PUT setting.json/taskReferences", ADMIN_OF_A,
            () -> fx.taskReferences.update(reference(B_HOME, B, "HOME_PAGE", "Taken Over")))).contains(REFUSED);
        assertThat(fx.probe("DELETE setting.json/taskReferences", ADMIN_OF_A, () -> fx.taskReferences.delete(B_GROUP))).contains(REFUSED);

        // ---- /kafkaConnectionProfile.json: B's profile and the platform's, by id; the list; A's default
        fx.probe("GET kafkaConnectionProfile.json/fetchAllProfiles", ADMIN_OF_A, fx.kafkaProfiles::fetchAllProfiles);
        for (long notMine : new long[] {B_PROFILE, PLATFORM_PROFILE}) {
            assertThat(fx.probe("PUT kafkaConnectionProfile.json/updateProfile", ADMIN_OF_A,
                () -> fx.kafkaProfiles.updateProfile(profile(notMine, "Taken Over")))).contains(REFUSED);
            assertThat(fx.probe("PUT kafkaConnectionProfile.json/deleteProfile", ADMIN_OF_A, () -> fx.kafkaProfiles.deleteProfile(notMine)))
                .contains(REFUSED);
            assertThat(fx.probe("POST kafkaConnectionProfile.json/setAsDefault", ADMIN_OF_A, () -> fx.kafkaProfiles.setAsDefault(notMine)))
                .contains(REFUSED);
            assertThat(fx.probe("POST kafkaConnectionProfile.json/testConnection", ADMIN_OF_A,
                () -> fx.kafkaProfiles.testConnection(profile(notMine, "Probe")))).contains(REFUSED);
            assertThat(fx.probe("GET kafkaConnectionProfile.json/testTopic", ADMIN_OF_A,
                () -> fx.kafkaProfiles.testTopic("bravo-secret-topic", notMine))).contains(REFUSED);
        }
        // Clearing "my" default clears A's, and neither B's nor the platform's.
        fx.probe("POST kafkaConnectionProfile.json/clearDefault", ADMIN_OF_A, fx.kafkaProfiles::clearDefault);
        // A new profile may not point at B's storage alias, nor at key material a person of B's uploaded.
        KafkaConnectionProfileDto theirAlias = profile(null, "Acme On Their Alias");
        theirAlias.setSecurityProtocol("SSL");
        theirAlias.setSslTruststoreBucket(B_STORAGE_ALIAS);
        theirAlias.setSslTruststoreLocation("acme/truststore.p12");
        assertThat(fx.probe("POST kafkaConnectionProfile.json/addProfile(their storage alias)", ADMIN_OF_A,
            () -> fx.kafkaProfiles.addProfile(theirAlias))).contains(REFUSED);
        KafkaConnectionProfileDto theirKeyMaterial = profile(null, "Acme On Their Certificate");
        theirKeyMaterial.setSecurityProtocol("SSL");
        theirKeyMaterial.setSslTruststoreBucket(CONFIG_BUCKET);
        theirKeyMaterial.setSslTruststoreLocation(B_SECRET_KEY);
        assertThat(fx.probe("POST kafkaConnectionProfile.json/addProfile(their key material)", ADMIN_OF_A,
            () -> fx.kafkaProfiles.addProfile(theirKeyMaterial))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaConnectionProfile.json/testConnection(their key material)", ADMIN_OF_A,
            () -> fx.kafkaProfiles.testConnection(theirKeyMaterial))).contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).as("nothing of B's or the platform's changed").isEqualTo(before);
        assertThat(fx.count("SELECT count(*) FROM kafka_connection_profile")).as("a refused profile writes nothing").isEqualTo(profilesBefore);
        assertThat(fx.count("SELECT count(*) FROM source_task_type")).as("a refused topic writes nothing").isEqualTo(topicsBefore);
        verify(fx.kafkaClients, never()).commonClientProps(argThat(CoreCrossTenantProbeSettingsAndKafkaPostgresTest::notA));
        verify(fx.kafkaClients, never()).ensureTopicExists(argThat(CoreCrossTenantProbeSettingsAndKafkaPostgresTest::notA), anyString(), anyInt());
        verify(fx.kafkaClients, never()).invalidate(eq(B_PROFILE));
        verify(fx.kafkaClients, never()).invalidate(eq(PLATFORM_PROFILE));
        verify(fx.trustedStorage, never()).readForWorkflow(any(), any(), eq(B_SECRET_KEY));
    }

    private static boolean notA(KafkaConnectionProfile profile) {
        return profile != null && !Long.valueOf(A).equals(profile.getTenantId());
    }

    /** What an A admin creates lands in A, whatever tenantId the body names. */
    @Test
    void settingsTheCallerCreatesLandInTheirWorkspaceWhateverTheBodyNames() throws Exception {
        SourceTaskTypeDto topicIntoB = topic(null, "Acme Topic Naming B", A_PROFILE);
        topicIntoB.setTenantId(B);
        assertThat(fx.probe("POST setting.json/addSourceTaskType(body names B)", ADMIN_OF_A,
            () -> fx.settings.addSourceTaskType(topicIntoB))).contains(SUCCEEDED);
        assertThat(fx.probe("POST setting.json/pipelineConfig(body names B)", ADMIN_OF_A,
            () -> fx.pipelineConfig.add(entry(null, B, "ACME_NAMING_B", "acme value naming b")))).contains(SUCCEEDED);
        assertThat(fx.probe("POST setting.json/taskReferences(body names B)", ADMIN_OF_A,
            () -> fx.taskReferences.add(reference(null, B, "HOME_PAGE", "Acme Home Naming B")))).contains(SUCCEEDED);
        KafkaConnectionProfileDto profileIntoB = profile(null, "Acme Kafka Naming B");
        profileIntoB.setTenantId(B);
        assertThat(fx.probe("POST kafkaConnectionProfile.json/addProfile(body names B)", ADMIN_OF_A,
            () -> fx.kafkaProfiles.addProfile(profileIntoB))).contains(SUCCEEDED);

        assertThat(fx.tenantOf("SELECT tenant_id FROM source_task_type WHERE service_name = ?", "Acme Topic Naming B")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM pipeline_config WHERE config_key = ?", "ACME_NAMING_B")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM task_reference WHERE name = ?", "Acme Home Naming B")).isEqualTo(A);
        assertThat(fx.tenantOf("SELECT tenant_id FROM kafka_connection_profile WHERE profile_name = ?", "Acme Kafka Naming B")).isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
    }

    /**
     * The business rule MIG-166 keeps distinct from the tenant rule: the platform default is seen only by a workspace
     * with no connection of its own, read-only; any workspace may USE it -- route a topic through it, or make a topic
     * on it -- and none may change, delete, re-default or test it. B's own connection is none of those things to C.
     */
    @Test
    void thePlatformDefaultIsSeenOnlyWithoutOneOfYourOwnAndMayBeUsedButNotChanged() throws Exception {
        String before = fx.foreignRows(B, GONE, DEFAULT_WS, UNKNOWN);

        // Seen: by C, which has none of its own, read-only and without its connection details; not by A, which has.
        String seenByC = fx.probe("GET kafkaConnectionProfile.json/fetchAllProfiles", ADMIN_OF_C, fx.kafkaProfiles::fetchAllProfiles);
        assertThat(seenByC).contains("Platform Default Kafka").contains("\"readOnly\":true").contains("\"platform\":true");
        assertThat(fx.probe("GET kafkaConnectionProfile.json/fetchAllProfiles", ADMIN_OF_A, fx.kafkaProfiles::fetchAllProfiles))
            .doesNotContain("Platform Default Kafka");
        fx.probe("GET setting.json/topicsForProfile(the platform default)", ADMIN_OF_C,
            () -> fx.settings.topicsForProfile(PLATFORM_PROFILE));

        // Not changed: not edited, deleted, made default or tested.
        assertThat(fx.probe("PUT kafkaConnectionProfile.json/updateProfile", ADMIN_OF_C,
            () -> fx.kafkaProfiles.updateProfile(profile(PLATFORM_PROFILE, "Taken Over")))).contains(REFUSED);
        assertThat(fx.probe("PUT kafkaConnectionProfile.json/deleteProfile", ADMIN_OF_C,
            () -> fx.kafkaProfiles.deleteProfile(PLATFORM_PROFILE))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaConnectionProfile.json/setAsDefault", ADMIN_OF_C,
            () -> fx.kafkaProfiles.setAsDefault(PLATFORM_PROFILE))).contains(REFUSED);
        assertThat(fx.probe("POST kafkaConnectionProfile.json/testConnection", ADMIN_OF_C,
            () -> fx.kafkaProfiles.testConnection(profile(PLATFORM_PROFILE, "Probe")))).contains(REFUSED);
        assertThat(fx.probe("GET kafkaConnectionProfile.json/testTopic", ADMIN_OF_C,
            () -> fx.kafkaProfiles.testTopic("charlie-topic", PLATFORM_PROFILE))).contains(REFUSED);

        // Used: a route through it, and a topic made on it -- accepted, and filed in C.
        assertThat(fx.probe("PUT setting.json/setKafkaRoute(the platform default)", ADMIN_OF_C,
            () -> fx.settings.setKafkaRoute(C_TYPE, PLATFORM_PROFILE))).contains(SUCCEEDED);
        assertThat(fx.probe("POST setting.json/addSourceTaskType(the platform default)", ADMIN_OF_C,
            () -> fx.settings.addSourceTaskType(topic(null, "Charlie On Platform", PLATFORM_PROFILE)))).contains(SUCCEEDED);
        assertThat(fx.count("SELECT count(*) FROM tenant_task_type_kafka_route WHERE tenant_id = ? AND source_task_type_id = ? "
            + "AND kafka_connection_profile_id = ?", C, C_TYPE, PLATFORM_PROFILE)).isEqualTo(1);
        assertThat(fx.tenantOf("SELECT tenant_id FROM source_task_type WHERE service_name = ?", "Charlie On Platform")).isEqualTo(C);
        // A, which has its own, may route through it all the same: use is not gated on having none.
        assertThat(fx.probe("PUT setting.json/setKafkaRoute(the platform default)", ADMIN_OF_A,
            () -> fx.settings.setKafkaRoute(A_TYPE, PLATFORM_PROFILE))).contains(SUCCEEDED);

        // B's own connection is not C's to use.
        assertThat(fx.probe("PUT setting.json/setKafkaRoute(their profile)", ADMIN_OF_C,
            () -> fx.settings.setKafkaRoute(C_TYPE, B_PROFILE))).contains(REFUSED);
        assertThat(fx.probe("POST setting.json/addSourceTaskType(their profile)", ADMIN_OF_C,
            () -> fx.settings.addSourceTaskType(topic(null, "Charlie On Bravo", B_PROFILE)))).contains(REFUSED);

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows(B, GONE, DEFAULT_WS, UNKNOWN)).as("the platform's row and B's are as they were").isEqualTo(before);
        verify(fx.kafkaClients, never()).invalidate(eq(PLATFORM_PROFILE));
    }

    /** A platform administrator's write must name a live workspace: not a deleted one, not an id with no tenant row. */
    @Test
    void aPlatformAdministratorCannotFileSettingsUnderAWorkspaceThatIsGoneOrNeverWas() throws Exception {
        assertThat(fx.rowsNaming(GONE, UNKNOWN)).isZero();
        for (long notLive : new long[] {GONE, UNKNOWN}) {
            SourceTaskTypeDto topicForIt = topic(null, "Platform Topic " + notLive, null);
            topicForIt.setTenantId(notLive);
            assertThat(fx.probe("POST setting.json/addSourceTaskType(workspace not live)", PLATFORM,
                () -> fx.settings.addSourceTaskType(topicForIt))).contains(REFUSED);
            assertThat(fx.probe("POST setting.json/pipelineConfig(workspace not live)", PLATFORM,
                () -> fx.pipelineConfig.add(entry(null, notLive, "PLATFORM_KEY_" + notLive, "a value")))).contains(REFUSED);
            assertThat(fx.probe("POST setting.json/taskReferences(workspace not live)", PLATFORM,
                () -> fx.taskReferences.add(reference(null, notLive, "HOME_PAGE", "Platform Home " + notLive)))).contains(REFUSED);
        }
        assertThat(fx.rowsNaming(GONE, UNKNOWN)).as("nothing filed under a workspace that is not live").isZero();
    }

    /**
     * The null-equals-null case: a token with a role and no workspace -- none named, or 0 or -1 -- owns nothing.
     * Every list is empty -- a list
     * that reads "no workspace named" as "every workspace" is a platform administrator's list handed to nobody in
     * particular -- the platform default is not shown to it (it has no workspace whose runs would go through it), and
     * it creates nothing: in particular no Kafka connection with tenant_id NULL, which would be the platform's.
     */
    @Test
    void aCallerWithNoWorkspaceGetsNothingOfAnyonesAndCreatesNothingThePlatformOwns() throws Exception {
        String before = fx.foreignRows(A, B, C, GONE, DEFAULT_WS, UNKNOWN, 0L, -1L);
        long profilesBefore = fx.count("SELECT count(*) FROM kafka_connection_profile");
        long topicsBefore = fx.count("SELECT count(*) FROM source_task_type");

        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessAdmin(none);
            fx.probe("GET setting.json/appSetting", caller, fx.settings::appSetting);
            fx.probe("GET setting.json/topics", caller, () -> fx.settings.topics(null, 50, null, null));
            fx.probe("GET setting.json/pipelineConfig", caller, () -> fx.pipelineConfig.list(null));
            fx.probe("GET setting.json/taskReferences", caller, () -> fx.taskReferences.list("HOME_PAGE", null));
            fx.probe("GET setting.json/taskReferences(groups)", caller, () -> fx.taskReferences.list("TASK_GROUP", null));
            fx.probe("GET kafkaConnectionProfile.json/fetchAllProfiles", caller, fx.kafkaProfiles::fetchAllProfiles);
            assertThat(fx.probe("GET setting.json/topicsForProfile(the platform default)", caller,
                () -> fx.settings.topicsForProfile(PLATFORM_PROFILE))).contains(REFUSED);
            assertThat(fx.probe("GET setting.json/profileHealth(the platform default)", caller,
                () -> fx.settings.profileHealth(PLATFORM_PROFILE, true))).contains(REFUSED);

            fx.probe("POST kafkaConnectionProfile.json/addProfile", caller,
                () -> fx.kafkaProfiles.addProfile(profile(null, "Orphan Kafka " + none)));
            fx.probe("POST setting.json/addSourceTaskType", caller,
                () -> fx.settings.addSourceTaskType(topic(null, "Orphan Topic " + none, null)));
            assertThat(fx.probe("POST setting.json/pipelineConfig", caller,
                () -> fx.pipelineConfig.add(entry(null, null, "ORPHAN_KEY", "orphan value")))).contains(REFUSED);
            assertThat(fx.probe("POST setting.json/taskReferences", caller,
                () -> fx.taskReferences.add(reference(null, null, "HOME_PAGE", "Orphan Home")))).contains(REFUSED);
            assertThat(fx.probe("PUT setting.json/setKafkaRoute(the platform default)", caller,
                () -> fx.settings.setKafkaRoute(A_TYPE, PLATFORM_PROFILE))).contains(REFUSED);
            fx.probe("POST kafkaConnectionProfile.json/clearDefault", caller, fx.kafkaProfiles::clearDefault);
        }

        assertThat(fx.leaks).isEmpty();
        assertThat(fx.count("SELECT count(*) FROM kafka_connection_profile"))
            .as("no connection filed under nobody -- tenant_id NULL is the platform's -- nor under 0 or -1").isEqualTo(profilesBefore);
        assertThat(fx.count("SELECT count(*) FROM source_task_type")).isEqualTo(topicsBefore);
        assertThat(fx.rowsNaming(0L, -1L)).as("nothing filed under a tenant id that names no workspace").isZero();
        assertThat(fx.foreignRows(A, B, C, GONE, DEFAULT_WS, UNKNOWN, 0L, -1L)).isEqualTo(before);
        assertThat(fx.count("SELECT count(*) FROM kafka_connection_profile WHERE status = ? AND tenant_id IS NULL AND is_default",
            Status.Active.name())).as("the platform default is still the one default").isEqualTo(1);
    }
}
