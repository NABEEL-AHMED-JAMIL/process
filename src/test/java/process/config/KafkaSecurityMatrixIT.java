package process.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.ObjectContentDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.service.StorageBrowserService;
import process.util.EncryptionUtil;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every security configuration the console can produce, against a real broker.
 *
 * The unit tests prove the client properties are assembled correctly and that a generated store
 * opens. Neither can prove the combination completes a TLS handshake, which is where this kind of
 * configuration actually goes wrong -- a truststore that opens locally and is still rejected by a
 * broker looks identical from inside the JVM.
 *
 * So this builds a KafkaConnectionProfile of each shape, runs it through the real
 * KafkaTemplateProvider, and asks a real AdminClient to describe a real cluster. Seven listeners,
 * one per security configuration, from docker/kafka-it.
 *
 * Named *IT rather than *Test so the ordinary `mvn test` does not run it: it needs docker, and a
 * suite that fails because a container is not running teaches people to ignore failures. Start the
 * stack with docker/kafka-it/start.sh; without it every case skips.
 *
 * @author Nabeel Ahmed
 * */
class KafkaSecurityMatrixIT {

    private static final Path SECRETS = Paths.get("docker/kafka-it/secrets");
    private static final Path ENV = Paths.get("docker/kafka-it/.env");
    private static final int PROBE_TIMEOUT_MS = 500;
    /** Long enough for a TLS handshake on a cold broker, short enough that a refusal is quick. */
    private static final int API_TIMEOUT_MS = 15000;

    private static String storePassword;
    private static String saslUser;
    private static String saslPassword;

    @BeforeAll
    static void loadStackSecrets() throws Exception {
        assumeTrue(Files.exists(SECRETS) && Files.exists(ENV),
            "docker/kafka-it is not set up -- run docker/kafka-it/start.sh");
        assumeTrue(listening(19092), "the kafka-it stack is not running -- run docker/kafka-it/start.sh");
        // kafka-clients 2.5 authenticates through Subject.getSubject(AccessController.getContext()),
        // which throws UnsupportedOperationException once the SecurityManager is gone in JDK 24+.
        // The image runs JDK 17 so production is unaffected, but a developer whose default JDK is
        // newer would otherwise see every SASL case fail with an unrelated-looking error.
        assumeTrue(majorJavaVersion() < 24, "kafka-clients 2.5 cannot do SASL on JDK 24+ "
            + "(Subject.getSubject was removed with the SecurityManager). Run this on JDK 17, as "
            + "the application image does: mvn test -Dtest=KafkaSecurityMatrixIT "
            + "-Djvm=$(/usr/libexec/java_home -v 17)/bin/java");

        storePassword = new String(Files.readAllBytes(SECRETS.resolve("store-password"))).trim();
        for (String line : Files.readAllLines(ENV)) {
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            String key = line.substring(0, equals);
            String value = line.substring(equals + 1).trim();
            if ("SASL_USER".equals(key)) saslUser = value;
            if ("SASL_PASSWORD".equals(key)) saslPassword = value;
        }
    }

    private static int majorJavaVersion() {
        String version = System.getProperty("java.specification.version", "17");
        int dot = version.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? version : version.substring(dot + 1));
        } catch (NumberFormatException ex) {
            return 17;
        }
    }

    private static boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", port), PROBE_TIMEOUT_MS);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * The real provider, with only the storage layer replaced.
     *
     * The stores it wants are on disk here rather than in a bucket, so readForWorkflow hands back
     * the local file. Everything else -- the property assembly, the JAAS escaping, the store type,
     * the on-disk caching -- is the code that runs in production.
     */
    private KafkaTemplateProvider provider(Path cacheDir) throws Exception {
        StorageBrowserService storage = mock(StorageBrowserService.class);
        when(storage.readForWorkflow(anyString(), anyString())).thenAnswer(call -> {
            byte[] bytes = Files.readAllBytes(SECRETS.resolve((String) call.getArgument(1)));
            return new ObjectContentDto(new ByteArrayInputStream(bytes),
                "application/octet-stream", bytes.length, "store");
        });

        EncryptionUtil encryption = new EncryptionUtil();
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        ReflectionTestUtils.setField(encryption, "base64Key",
            Base64.getEncoder().encodeToString(generator.generateKey().getEncoded()));

        KafkaTemplateProvider provider = new KafkaTemplateProvider(encryption, null, null, storage);
        ReflectionTestUtils.setField(provider, "secretCacheDir", cacheDir.toString());
        ReflectionTestUtils.setField(provider, "localStoreDir", "");
        ReflectionTestUtils.setField(provider, "defaultReplicationFactor", (short) 1);
        this.encryption = encryption;
        return provider;
    }

    private EncryptionUtil encryption;

    private KafkaConnectionProfile profile(long id, int port, String protocol) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(id);
        profile.setProfileName("it-" + port);
        profile.setBootstrapServers("localhost:" + port);
        profile.setSecurityProtocol(protocol);
        return profile;
    }

    private void withSasl(KafkaConnectionProfile profile, String mechanism) {
        profile.setSaslMechanism(mechanism);
        profile.setSaslUsername(saslUser);
        profile.setSaslPassword(this.encryption.encrypt(saslPassword));
    }

    private void withTruststore(KafkaConnectionProfile profile, String file) {
        profile.setSslTruststoreBucket("etl-bucket");
        profile.setSslTruststoreLocation(file);
        profile.setSslTruststorePasswordEnc(this.encryption.encrypt(storePassword));
    }

    private void withKeystore(KafkaConnectionProfile profile, String file) {
        profile.setSslKeystoreBucket("etl-bucket");
        profile.setSslKeystoreLocation(file);
        profile.setSslKeystorePasswordEnc(this.encryption.encrypt(storePassword));
        profile.setSslKeyPasswordEnc(this.encryption.encrypt(storePassword));
    }

    /** Describes the cluster, which is the cheapest exchange that still requires a full handshake. */
    private String describeCluster(Map<String, Object> props) throws Exception {
        Properties client = new Properties();
        client.putAll(props);
        client.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, API_TIMEOUT_MS);
        client.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, API_TIMEOUT_MS);
        try (AdminClient admin = AdminClient.create(client)) {
            return admin.describeCluster().clusterId().get(API_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
    }

    // ---- the matrix -------------------------------------------------------------------------

    static Stream<Arguments> everySecurityConfiguration() {
        return Stream.of(
            Arguments.of("PLAINTEXT, no security at all", 19092, "PLAINTEXT", null, null, null),
            // The same listener reached with each store format, because the client has to declare
            // ssl.truststore.type and the two answers differ.
            Arguments.of("SSL with a PKCS12 truststore", 19093, "SSL", null, "client.truststore.p12", null),
            Arguments.of("SSL with a JKS truststore", 19093, "SSL", null, "client.truststore.jks", null),
            Arguments.of("mutual TLS, PKCS12 both sides", 19094, "SSL", null, "client.truststore.p12", "client.keystore.p12"),
            Arguments.of("mutual TLS, JKS both sides", 19094, "SSL", null, "client.truststore.jks", "client.keystore.jks"),
            Arguments.of("mutual TLS, PKCS12 trust and JKS key", 19094, "SSL", null, "client.truststore.p12", "client.keystore.jks"),
            Arguments.of("SASL_PLAINTEXT with PLAIN", 19095, "SASL_PLAINTEXT", "PLAIN", null, null),
            Arguments.of("SASL_SSL with PLAIN", 19096, "SASL_SSL", "PLAIN", "client.truststore.p12", null),
            Arguments.of("SASL_PLAINTEXT with SCRAM-SHA-256", 19097, "SASL_PLAINTEXT", "SCRAM-SHA-256", null, null),
            Arguments.of("SASL_SSL with SCRAM-SHA-512", 19098, "SASL_SSL", "SCRAM-SHA-512", "client.truststore.jks", null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("everySecurityConfiguration")
    @DisplayName("connects to a real broker")
    void connects(String description, int port, String protocol, String mechanism,
        String truststore, String keystore) throws Exception {
        assumeTrue(listening(port), "listener " + port + " is not up");
        Path cache = Files.createTempDirectory("kafka-it-");

        KafkaTemplateProvider provider = this.provider(cache);
        KafkaConnectionProfile profile = this.profile(port * 10L, port, protocol);
        if (mechanism != null) this.withSasl(profile, mechanism);
        if (truststore != null) this.withTruststore(profile, truststore);
        if (keystore != null) this.withKeystore(profile, keystore);

        Map<String, Object> props = provider.commonClientProps(profile);
        assertThat(this.describeCluster(props))
            .as("%s should reach the broker", description)
            .isNotBlank();
    }

    // ---- the refusals, which matter as much as the connections -------------------------------

    /** Without the CA the broker's certificate is untrusted, and the handshake must fail. */
    @Test
    void refusesAnSslBrokerWhenTheTruststoreIsMissing() throws Exception {
        assumeTrue(listening(19093), "listener 19093 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(1L, 19093, "SSL");

        Map<String, Object> props = provider.commonClientProps(profile);

        assertThatThrownBy(() -> this.describeCluster(props)).isInstanceOf(Exception.class);
    }

    /** The mutual-TLS listener demands a client certificate; a truststore alone is not enough. */
    @Test
    void refusesTheMutualTlsListenerWithNoClientCertificate() throws Exception {
        assumeTrue(listening(19094), "listener 19094 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(2L, 19094, "SSL");
        this.withTruststore(profile, "client.truststore.p12");

        Map<String, Object> props = provider.commonClientProps(profile);

        assertThatThrownBy(() -> this.describeCluster(props)).isInstanceOf(Exception.class);
    }

    @Test
    void refusesSaslWithTheWrongPassword() throws Exception {
        assumeTrue(listening(19095), "listener 19095 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(3L, 19095, "SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername(saslUser);
        profile.setSaslPassword(this.encryption.encrypt("not-the-password"));

        Map<String, Object> props = provider.commonClientProps(profile);

        assertThatThrownBy(() -> this.describeCluster(props))
            .isInstanceOfAny(ExecutionException.class, org.apache.kafka.common.KafkaException.class);
    }

    /**
     * A password with a quote and a backslash in it.
     *
     * sasl.jaas.config is a string the client parses, so an unescaped quote either breaks the
     * config or closes the entry early and lets whatever follows be read as another JAAS option.
     * The escaping is unit-tested; this proves the escaped form is what a broker actually accepts,
     * by failing authentication rather than failing to build a client.
     */
    @Test
    void aPasswordFullOfQuotesStillProducesAConfigTheClientCanParse() throws Exception {
        assumeTrue(listening(19095), "listener 19095 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(4L, 19095, "SASL_PLAINTEXT");
        profile.setSaslMechanism("PLAIN");
        profile.setSaslUsername(saslUser);
        profile.setSaslPassword(this.encryption.encrypt("a\"b\\c required extra=\"x\";"));

        Map<String, Object> props = provider.commonClientProps(profile);

        // Rejected by the broker as a wrong password -- not rejected by the client as a malformed
        // JAAS entry, which is what an unescaped quote would produce.
        assertThatThrownBy(() -> this.describeCluster(props))
            .isInstanceOfAny(ExecutionException.class, org.apache.kafka.common.KafkaException.class);
    }

    /** additionalProperties must not be able to restate the security settings. */
    @Test
    void additionalPropertiesCannotDowngradeTheProtocol() throws Exception {
        assumeTrue(listening(19093), "listener 19093 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(5L, 19093, "SSL");
        this.withTruststore(profile, "client.truststore.p12");
        profile.setAdditionalProperties("{\"security.protocol\":\"PLAINTEXT\"}");

        Map<String, Object> props = provider.commonClientProps(profile);

        assertThat(props.get("security.protocol")).isEqualTo("SSL");
        assertThat(this.describeCluster(props)).isNotBlank();
    }

    /** The declared store type has to match the file, whichever format it is. */
    @ParameterizedTest(name = "{0} is declared as {1}")
    @org.junit.jupiter.params.provider.CsvSource({
        "client.truststore.p12, PKCS12",
        "client.truststore.jks, JKS" })
    void declaresTheStoreTypeThatMatchesTheFile(String file, String expectedType) throws Exception {
        assumeTrue(listening(19093), "listener 19093 is not up");
        KafkaTemplateProvider provider = this.provider(Files.createTempDirectory("kafka-it-"));
        KafkaConnectionProfile profile = this.profile(6L, 19093, "SSL");
        this.withTruststore(profile, file);

        Map<String, Object> props = provider.commonClientProps(profile);

        assertThat(props.get("ssl.truststore.type")).isEqualTo(expectedType);
        assertThat(this.describeCluster(props)).isNotBlank();
    }


}
