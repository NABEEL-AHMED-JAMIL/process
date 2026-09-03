package process.e2e;

import org.jodconverter.core.document.DocumentFormatRegistry;
import org.jodconverter.core.office.OfficeManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.KafkaSecretDto;
import process.model.dto.ResponseDto;
import process.model.enums.KafkaSecretKind;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.TenantRepository;
import process.model.service.KafkaConnectionProfileService;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Provisions one Kafka connection profile of every security configuration for a real tenant, then
 * tests each one against the local broker stack and prints what happened.
 *
 * A driver, not a test of the application: it writes rows that are meant to survive so somebody can
 * open the console and look at them. That is why it does not roll back, and why it is disabled
 * unless asked for by name --
 *
 *     ./run-kafka-provisioning.sh
 *
 * -- so a plain `mvn test` never creates anything. Everything it writes is listed at the end so it
 * can be undone.
 *
 * It goes through the real services, so every profile is validated, every secret encrypted with the
 * application's own key, and every certificate stored at the path the server chooses. The store
 * files land in the platform bucket for real and are NOT rolled back, because a profile pointing at
 * a truststore that no longer exists would be worse than useless.
 *
 * Needs kafka-it running: kafka-it/start.sh
 *
 * @author Nabeel Ahmed
 * */
@SpringBootTest
@ActiveProfiles("e2e")
@EnabledIfSystemProperty(named = "provisionKafka", matches = "true")
class AjwaKafkaProvisioningDriver {

    private static final Path SECRETS = Paths.get("kafka-it/secrets");
    private static final Path STACK_ENV = Paths.get("kafka-it/.env");
    /** The application runs in a container, so it reaches the published ports by this name. */
    private static final String BROKER_HOST = "host.docker.internal";
    private static final String TENANT_NAME = "Ajwa LLC";

    @MockBean private OfficeManager officeManager;
    @MockBean private DocumentFormatRegistry documentFormatRegistry;

    @Autowired private KafkaConnectionProfileService profileService;
    @Autowired private KafkaSecretService secretService;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private AppUserRepository appUserRepository;
    @Autowired private KafkaConnectionProfileRepository profileRepository;

    private final List<String> created = new ArrayList<>();
    private final List<String> results = new ArrayList<>();

    private String saslUser;
    private String saslPassword;

    @Test
    void provisionEverySecurityConfigurationAndTestEachOne() throws Exception {
        this.readStackCredentials();
        AppUser admin = this.ajwaAdministrator();
        // Everything below runs as that person, so the services scope exactly as they would for a
        // request: the profiles land in Ajwa's tenant and the certificates under the admin's own id.
        TenantContext.set(admin.getTenantId(), admin.getUserRole().name(),
            admin.getAppUserId(), admin.getUsername());
        try {
            KafkaSecretDto truststore = this.uploadAndBuildTruststore();
            KafkaSecretDto keystore = this.uploadAndBuildKeystore();

            this.plaintext();
            this.ssl(truststore);
            this.mutualTls(truststore, keystore);
            this.sasl("SASL_PLAINTEXT", "PLAIN", 19095, null);
            this.sasl("SASL_SSL", "PLAIN", 19096, truststore);
            this.sasl("SASL_PLAINTEXT", "SCRAM-SHA-256", 19097, null);
            this.sasl("SASL_SSL", "SCRAM-SHA-512", 19098, truststore);
        } finally {
            TenantContext.clear();
        }
        this.report();
    }

    // ---- the tenant ------------------------------------------------------------------------

    private AppUser ajwaAdministrator() {
        Tenant ajwa = this.tenantRepository.findAll().stream()
            .filter(t -> TENANT_NAME.equalsIgnoreCase(t.getTenantName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "No tenant called '" + TENANT_NAME + "'. Create it in the console first."));
        return this.appUserRepository
            .findByTenantIdAndStatusNotOrderByAppUserIdDesc(ajwa.getTenantId(), Status.Delete).stream()
            .filter(u -> u.getUserRole() == UserRole.TENANT_ADMIN)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "'" + TENANT_NAME + "' has no TENANT_ADMIN to own these profiles."));
    }

    private void readStackCredentials() throws Exception {
        if (!Files.exists(STACK_ENV)) {
            throw new IllegalStateException("kafka-it is not set up -- run kafka-it/start.sh");
        }
        for (String line : Files.readAllLines(STACK_ENV)) {
            int equals = line.indexOf('=');
            if (equals < 0) continue;
            if ("SASL_USER".equals(line.substring(0, equals))) this.saslUser = line.substring(equals + 1).trim();
            if ("SASL_PASSWORD".equals(line.substring(0, equals))) this.saslPassword = line.substring(equals + 1).trim();
        }
    }

    // ---- certificates ----------------------------------------------------------------------

    private MockMultipartFile pem(String name, String label, byte[] der) {
        String body = java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
            .encodeToString(der);
        String text = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
        return new MockMultipartFile("file", name, "application/x-pem-file",
            text.getBytes(StandardCharsets.UTF_8));
    }

    private KafkaSecretDto uploadAndBuildTruststore() throws Exception {
        byte[] ca = Files.readAllBytes(SECRETS.resolve("ca.crt"));
        ResponseDto uploaded = this.secretService.uploadSecret(
            new MockMultipartFile("file", "ca.pem", "application/x-pem-file", ca),
            KafkaSecretKind.CA_CERTIFICATE);
        assertThat(uploaded.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto certificate = (KafkaSecretDto) uploaded.getData();
        this.created.add("object  " + certificate.getBucket() + "/" + certificate.getObjectKey());

        ResponseDto built = this.secretService.generateTruststore(
            Collections.singletonList(certificate.getObjectKey()));
        assertThat(built.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto truststore = (KafkaSecretDto) built.getData();
        this.created.add("object  " + truststore.getBucket() + "/" + truststore.getObjectKey());
        return truststore;
    }

    private KafkaSecretDto uploadAndBuildKeystore() throws Exception {
        ResponseDto cert = this.secretService.uploadSecret(
            new MockMultipartFile("file", "client.crt", "application/x-pem-file",
                Files.readAllBytes(SECRETS.resolve("client.crt"))),
            KafkaSecretKind.CLIENT_CERTIFICATE);
        assertThat(cert.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto certificate = (KafkaSecretDto) cert.getData();
        this.created.add("object  " + certificate.getBucket() + "/" + certificate.getObjectKey());

        // PKCS#8, which is what the uploader accepts -- generate-certs.sh writes this alongside
        // the openssl default so the pair is usable without conversion.
        ResponseDto key = this.secretService.uploadSecret(
            new MockMultipartFile("file", "client.key", "application/x-pem-file",
                Files.readAllBytes(SECRETS.resolve("client.pkcs8.key"))),
            KafkaSecretKind.CLIENT_PRIVATE_KEY);
        assertThat(key.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto privateKey = (KafkaSecretDto) key.getData();
        this.created.add("object  " + privateKey.getBucket() + "/" + privateKey.getObjectKey());

        ResponseDto built = this.secretService.generateKeystore(
            certificate.getObjectKey(), privateKey.getObjectKey());
        assertThat(built.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto keystore = (KafkaSecretDto) built.getData();
        this.created.add("object  " + keystore.getBucket() + "/" + keystore.getObjectKey());
        return keystore;
    }

    // ---- the profiles ----------------------------------------------------------------------

    private KafkaConnectionProfileDto base(String name, String label, int port, String protocol) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName(name);
        dto.setEnvironmentLabel(label);
        dto.setBootstrapServers(BROKER_HOST + ":" + port);
        dto.setSecurityProtocol(protocol);
        dto.setStatus(Status.Active);
        return dto;
    }

    private void withTruststore(KafkaConnectionProfileDto dto, KafkaSecretDto truststore) {
        dto.setSslTruststoreBucket(truststore.getBucket());
        dto.setSslTruststoreLocation(truststore.getObjectKey());
        // Already ciphertext from the generator, so it goes into the passthrough field rather than
        // the one the save path would encrypt a second time.
        dto.setSslTruststorePasswordEnc(truststore.getStorePasswordEnc());
    }

    private void save(KafkaConnectionProfileDto dto) throws Exception {
        ResponseDto saved = this.profileService.addProfile(dto);
        if (!SUCCESS.equals(saved.getStatus())) {
            this.results.add(String.format("%-38s SAVE REFUSED: %s", dto.getProfileName(), saved.getMessage()));
            return;
        }
        this.created.add("profile " + dto.getProfileName());

        // The saved row's id, so the outcome is recorded ON that profile. Without it the service
        // takes the unsaved-profile branch: the connection is genuinely tested, but nothing is
        // written back, and the console goes on showing "Untested" for a profile that works.
        Long savedId = this.profileRepository.findAll().stream()
            .filter(p -> dto.getProfileName().equals(p.getProfileName()))
            .filter(p -> p.getStatus() != Status.Delete)
            .map(process.model.pojo.KafkaConnectionProfile::getKafkaConnectionProfileId)
            .max(Long::compareTo)
            .orElse(null);
        if (savedId == null) {
            // Recorded rather than thrown, so the configurations after this one are still
            // provisioned; report() is what turns it red at the end.
            this.results.add(String.format("%-38s NO SAVED ROW: saved, but no row came back to"
                + " record the connection test on", dto.getProfileName()));
            return;
        }

        KafkaConnectionProfileDto probe = new KafkaConnectionProfileDto();
        probe.setKafkaConnectionProfileId(savedId);
        probe.setProfileName(dto.getProfileName());
        probe.setBootstrapServers(dto.getBootstrapServers());
        probe.setSecurityProtocol(dto.getSecurityProtocol());
        probe.setSaslMechanism(dto.getSaslMechanism());
        probe.setSaslUsername(dto.getSaslUsername());
        probe.setSaslPassword(dto.getSaslPassword());
        probe.setSslTruststoreBucket(dto.getSslTruststoreBucket());
        probe.setSslTruststoreLocation(dto.getSslTruststoreLocation());
        probe.setSslTruststorePasswordEnc(dto.getSslTruststorePasswordEnc());
        probe.setSslKeystoreBucket(dto.getSslKeystoreBucket());
        probe.setSslKeystoreLocation(dto.getSslKeystoreLocation());
        probe.setSslKeystorePasswordEnc(dto.getSslKeystorePasswordEnc());
        probe.setSslKeyPasswordEnc(dto.getSslKeyPasswordEnc());

        ResponseDto tested = this.profileService.testConnection(probe);
        this.results.add(String.format("%-38s %-8s %s", dto.getProfileName(),
            SUCCESS.equals(tested.getStatus()) ? "OK" : "FAILED", tested.getMessage()));
    }

    private void plaintext() throws Exception {
        this.save(this.base("Ajwa - PLAINTEXT", "local", 19092, "PLAINTEXT"));
    }

    private void ssl(KafkaSecretDto truststore) throws Exception {
        KafkaConnectionProfileDto dto = this.base("Ajwa - SSL (private CA)", "local", 19093, "SSL");
        this.withTruststore(dto, truststore);
        this.save(dto);
    }

    private void mutualTls(KafkaSecretDto truststore, KafkaSecretDto keystore) throws Exception {
        KafkaConnectionProfileDto dto = this.base("Ajwa - SSL mutual TLS", "local", 19094, "SSL");
        this.withTruststore(dto, truststore);
        dto.setSslKeystoreBucket(keystore.getBucket());
        dto.setSslKeystoreLocation(keystore.getObjectKey());
        dto.setSslKeystorePasswordEnc(keystore.getStorePasswordEnc());
        dto.setSslKeyPasswordEnc(keystore.getStorePasswordEnc());
        this.save(dto);
    }

    private void sasl(String protocol, String mechanism, int port, KafkaSecretDto truststore) throws Exception {
        KafkaConnectionProfileDto dto = this.base(
            "Ajwa - " + protocol + " / " + mechanism, "local", port, protocol);
        dto.setSaslMechanism(mechanism);
        dto.setSaslUsername(this.saslUser);
        dto.setSaslPassword(this.saslPassword);
        if (truststore != null) {
            this.withTruststore(dto, truststore);
        }
        this.save(dto);
    }

    private void report() {
        StringBuilder out = new StringBuilder("\n");
        out.append("=========== Ajwa LLC: Kafka connection profiles ===========\n");
        for (String line : this.results) {
            out.append("  ").append(line).append('\n');
        }
        out.append("\n--- created, and left in place for you to look at ---\n");
        for (String line : this.created) {
            out.append("  ").append(line).append('\n');
        }
        out.append("===========================================================\n");
        System.out.println(out);

        // save() appends exactly one line per configuration, so counting them could never fail --
        // it says only that the method ran to the end, which an exception would already have told
        // us. What is worth failing on is whether each profile was actually provisioned: a broker
        // that is down reads as FAILED above and is the stack's business, but a profile the
        // application itself refused, or saved without a row to hang the result on, is the
        // application's, and this driver exists to find that out.
        assertThat(this.results).as("every configuration should have been attempted").hasSize(7);
        assertThat(this.results)
            .as("every profile should have been saved -- see the report above")
            .noneMatch(line -> line.contains("SAVE REFUSED") || line.contains("NO SAVED ROW"));
    }

}
