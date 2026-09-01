package process.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import process.model.dto.ObjectContentDto;
import process.model.pojo.KafkaConnectionProfile;
import process.model.service.StorageBrowserService;
import process.util.EncryptionUtil;
import process.util.KafkaCertificateUtil;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class KafkaTemplateProvider {

    private final Logger logger = LoggerFactory.getLogger(KafkaTemplateProvider.class);
    private final Gson gson = new Gson();

    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplate<String, String> fallbackTemplate;
    private final KafkaProperties kafkaProperties;
    private final StorageBrowserService storageBrowserService;

    /**
     * Where downloaded truststores and keystores are cached. Left blank this falls back to the
     * JVM temp dir, which on a shared host is readable by everything else running there, so a
     * deployment holding real key material should point this at a directory of its own.
     */
    @Value("${kafka.secret-cache.dir:}")
    private String secretCacheDir;

    /**
     * A store named without a bucket is a raw path on the application host rather than an object
     * the storage layer authorised. Blank -- the default -- means no such path is allowed at all.
     */
    @Value("${kafka.ssl.local-store-dir:}")
    private String localStoreDir;

    /**
     * Replication factor for topics this application auto-creates. One is right for a single
     * broker on a laptop and wrong everywhere else; -1 lets the broker apply its own default.
     */
    @Value("${kafka.topic.default-replication-factor:1}")
    private short defaultReplicationFactor;

    /** rwx------ : the only mode a directory holding private keys should ever have. */
    private static final Set<PosixFilePermission> OWNER_ONLY = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));

    private final Map<Long, CachedProducer> cache = new ConcurrentHashMap<>();

    public KafkaTemplateProvider(EncryptionUtil encryptionUtil,
        KafkaTemplate<String, String> fallbackTemplate, KafkaProperties kafkaProperties,
        StorageBrowserService storageBrowserService) {
        this.encryptionUtil = encryptionUtil;
        this.fallbackTemplate = fallbackTemplate;
        this.kafkaProperties = kafkaProperties;
        this.storageBrowserService = storageBrowserService;
    }

    public KafkaTemplate<String, String> getTemplate(Optional<KafkaConnectionProfile> profile) {
        if (!profile.isPresent()) {
            return this.fallbackTemplate;
        }
        KafkaConnectionProfile p = profile.get();
        CachedProducer cached = this.cache.computeIfAbsent(p.getKafkaConnectionProfileId(), id -> {
            this.logger.info("Building KafkaTemplate for profile '{}' ({}), tenantId={}.",
                p.getProfileName(), p.getBootstrapServers(), p.getTenantId());
            DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(this.producerProps(p));
            return new CachedProducer(factory, new KafkaTemplate<>(factory));
        });
        return cached.template;
    }

    public void invalidate(Long kafkaConnectionProfileId) {
        CachedProducer removed = this.cache.remove(kafkaConnectionProfileId);
        if (removed != null) {
            try {
                removed.factory.destroy();
            } catch (Exception ex) {
                this.logger.warn("Error closing Kafka producer factory for profile {}: {}", kafkaConnectionProfileId, ex.getMessage());
            }
        }
        this.deleteQuietlyRecursive(this.secretCacheRoot().resolve(String.valueOf(kafkaConnectionProfileId)));
    }

    public Map<String, Object> commonClientProps(KafkaConnectionProfile profile) {
        Map<String, Object> props = new HashMap<>();
        // Tuning the operator typed by hand goes on first, so that nothing in it can restate where
        // we connect, how we authenticate or whether the wire is encrypted -- the profile decides
        // all three below, and only the profile's own columns are checked before it is saved.
        this.mergeAdditionalProperties(props, profile.getAdditionalProperties());
        props.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, profile.getBootstrapServers());
        String securityProtocol = profile.getSecurityProtocol();
        if (securityProtocol != null && !securityProtocol.trim().isEmpty()) {
            props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, securityProtocol);
        }
        if (securityProtocol != null && securityProtocol.startsWith("SASL_")) {
            props.put(SaslConfigs.SASL_MECHANISM, profile.getSaslMechanism());
            String username = profile.getSaslUsername();
            String password = profile.getSaslPassword() == null ? null : this.encryptionUtil.decrypt(profile.getSaslPassword());
            String loginModule = "SCRAM-SHA-256".equals(profile.getSaslMechanism()) || "SCRAM-SHA-512".equals(profile.getSaslMechanism())
                ? "org.apache.kafka.common.security.scram.ScramLoginModule"
                : "org.apache.kafka.common.security.plain.PlainLoginModule";
            props.put(SaslConfigs.SASL_JAAS_CONFIG, String.format(
                "%s required username=\"%s\" password=\"%s\";", loginModule,
                this.jaasEscape(username), this.jaasEscape(password)));
        }
        if ("SSL".equals(securityProtocol) || "SASL_SSL".equals(securityProtocol)) {
            if (profile.getSslTruststoreLocation() != null && !profile.getSslTruststoreLocation().trim().isEmpty()) {
                String localPath = this.resolveLocalSecretFile(
                    profile, "truststore", profile.getSslTruststoreBucket(), profile.getSslTruststoreLocation());
                props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, localPath);
                props.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG,
                    this.storeTypeOf(profile.getSslTruststoreLocation()));
                if (profile.getSslTruststorePasswordEnc() != null) {
                    props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslTruststorePasswordEnc()));
                }
            }
            if (profile.getSslKeystoreLocation() != null && !profile.getSslKeystoreLocation().trim().isEmpty()) {
                String localPath = this.resolveLocalSecretFile(
                    profile, "keystore", profile.getSslKeystoreBucket(), profile.getSslKeystoreLocation());
                props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, localPath);
                props.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG,
                    this.storeTypeOf(profile.getSslKeystoreLocation()));
                if (profile.getSslKeystorePasswordEnc() != null) {
                    props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslKeystorePasswordEnc()));
                }
                if (profile.getSslKeyPasswordEnc() != null) {
                    props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, this.encryptionUtil.decrypt(profile.getSslKeyPasswordEnc()));
                }
            }

            if (profile.getSslEndpointIdentificationAlgorithm() != null) {
                props.put(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG, profile.getSslEndpointIdentificationAlgorithm());
            }
        }
        return props;
    }

    /**
     * Which format a store file is in.
     *
     * Kafka defaults ssl.*.type to JKS while every store this application generates is PKCS12, so
     * without this the two disagree. On the JDK 17 the image runs that disagreement is currently
     * survivable -- the security property keystore.type.compat defaults to true, and a JKS-declared
     * store quietly reads a PKCS12 file anyway. It stops being survivable on a Java 8 runtime,
     * which this source level still permits, or wherever that property is turned off, and the
     * failure then lands at the first handshake rather than anywhere near the upload.
     *
     * Declaring it is the honest thing regardless: the file's format is known at this point and
     * relying on a compatibility shim to paper over a wrong answer is not the same as giving the
     * right one. The type is read off the extension because that is what distinguishes the two on
     * disk, so a store somebody built with keytool keeps whichever format they made.
     *
     * PKCS12 is the fallback rather than JKS: it is what is generated here, it is the JDK default
     * from 9 onwards, and an unrecognised extension is likelier to be a renamed .p12 than a JKS.
     */
    private String storeTypeOf(String location) {
        String name = location == null ? "" : location.trim().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jks")) {
            return "JKS";
        }
        return KafkaCertificateUtil.STORE_TYPE;
    }

    private String resolveLocalSecretFile(KafkaConnectionProfile profile, String kind, String bucket, String objectKey) {
        if (bucket == null || bucket.trim().isEmpty()) {
            return this.confinedLocalPath(kind, objectKey);
        }
        Long profileId = profile.getKafkaConnectionProfileId();
        Path localFile = this.secretCacheFile(this.verifiedSecretCacheRoot(), profileId, kind, bucket, objectKey);
        // An unsaved profile -- Test Connection on a dialog that has never been saved -- has no id
        // to cache under, so its download is private to the call and never read back.
        if (profileId != null && Files.exists(localFile)) {
            return localFile.toString();
        }
        try {
            this.createPrivateDirectories(localFile.getParent());
            if (profileId == null) {
                localFile.getParent().toFile().deleteOnExit();
                localFile.toFile().deleteOnExit();
            }
            ObjectContentDto content = this.downloadProfileSecret(bucket, objectKey);
            this.writePrivateFile(content.getContent(), localFile);
            this.logger.info("Cached {} for Kafka profile {} from bucket {}/{} -> {}", kind, profileId, bucket, objectKey, localFile);
            return localFile.toString();
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException(
                "Could not download " + kind + " from bucket " + bucket + "/" + objectKey + " for Kafka profile " + profileId, ex);
        }
    }

    /**
     * Fetches a profile's TLS material through the storage layer's trusted path.
     *
     * Not the ordinary download, for two reasons. Scheduler and startup threads carry no
     * TenantContext at all, so a browse-style call has nobody to authorise and refuses -- which is
     * why an SSL profile could be tested successfully from the console and then fail on every
     * dispatch afterwards. And the material usually sits in the platform's own bucket, which the
     * browse path now refuses to anyone who is not a platform admin, as it should.
     *
     * What makes the trusted call correct here is that neither the bucket nor the key comes from
     * a caller: both are read off the profile row, which the caller already had to be entitled to
     * before it could be loaded. Standing in as the profile's tenant was the earlier attempt at
     * this and it was the wrong shape -- it fabricated a principal, and still lost to the platform
     * bucket.
     */
    private ObjectContentDto downloadProfileSecret(String bucket, String objectKey) {
        return this.storageBrowserService.readForWorkflow(bucket, objectKey);
    }

    /**
     * Kafka opens whatever path it is handed, so a store named without a bucket may only ever sit
     * under a directory the operator nominated. The failure says nothing about the path asked for:
     * "missing" and "not a keystore" read differently, and that difference is a way to probe the
     * host's filesystem one path at a time.
     */
    private String confinedLocalPath(String kind, String objectKey) {
        if (this.localStoreDir == null || this.localStoreDir.trim().isEmpty()) {
            throw new IllegalStateException("A storage bucket is required for the Kafka " + kind
                + "; local store paths are only served from kafka.ssl.local-store-dir.");
        }
        Path base = this.canonical(Paths.get(this.localStoreDir.trim()));
        Path candidate = this.canonical(base.resolve(objectKey));
        if (!candidate.startsWith(base)) {
            throw new IllegalStateException("The Kafka " + kind + " path is outside kafka.ssl.local-store-dir.");
        }
        return candidate.toString();
    }

    private Path canonical(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        try {
            return Files.exists(normalized) ? normalized.toRealPath() : normalized;
        } catch (IOException ex) {
            // Unreadable is not resolvable, and an unresolved path must not be treated as confined.
            throw new IllegalStateException("Could not resolve the configured Kafka store directory.", ex);
        }
    }

    private Path secretCacheRoot() {
        if (this.secretCacheDir != null && !this.secretCacheDir.trim().isEmpty()) {
            return Paths.get(this.secretCacheDir.trim());
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), "kafka-secrets-cache");
    }

    /**
     * The cache root, checked rather than assumed, before any key material goes under it.
     *
     * Every name below it is derivable by anyone who can read the profile row, and the default sits
     * in the shared temp dir, so a local account that creates the root first owns the parent of
     * every profile's secrets: it can read what is written there, and it can plant a file at the
     * name a later connection will pick up as that profile's trust anchor. Only a directory this
     * process owns and nobody else can enter is usable, and one that is not fails the connection
     * rather than being quietly used.
     */
    private Path verifiedSecretCacheRoot() {
        Path root = this.secretCacheRoot();
        try {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                this.createPrivateDirectories(root);
                return root;
            }
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("The Kafka secret cache path must be a directory of its own, not a file or a link.");
            }
            PosixFileAttributeView view = Files.getFileAttributeView(
                root, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                // Non-POSIX filesystem: there are no ownership or mode bits here to judge it by.
                return root;
            }
            PosixFileAttributes attributes = view.readAttributes();
            UserPrincipal self = root.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
            if (!attributes.owner().equals(self)) {
                throw new IllegalStateException("The Kafka secret cache directory belongs to another account; "
                    + "point kafka.secret-cache.dir at one of this application's own.");
            }
            // Writable by anyone else is not recoverable: something may already have been placed
            // in here, and narrowing the directory now would lock that in rather than remove it.
            if (attributes.permissions().contains(PosixFilePermission.GROUP_WRITE)
                || attributes.permissions().contains(PosixFilePermission.OTHERS_WRITE)) {
                throw new IllegalStateException("The Kafka secret cache directory is writable by other "
                    + "accounts and its contents cannot be trusted; point kafka.secret-cache.dir at a "
                    + "private directory.");
            }
            // Merely readable is recoverable, and refusing was a trap: a deployment upgraded from a
            // build predating this check finds the directory already there at 0755, and every TLS
            // connection then fails for good with a message that names a property rather than the
            // directory. Nothing could have been planted, only read, so it is narrowed and used.
            if (!OWNER_ONLY.equals(attributes.permissions())) {
                try {
                    Files.setPosixFilePermissions(root, OWNER_ONLY);
                    this.logger.info("Narrowed the Kafka secret cache directory {} to owner-only access.", root);
                } catch (IOException cannotNarrow) {
                    throw new IllegalStateException("The Kafka secret cache directory is open to other accounts "
                        + "and could not be narrowed; point kafka.secret-cache.dir at a private directory.",
                        cannotNarrow);
                }
            }
            return root;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not verify the Kafka secret cache directory.", ex);
        }
    }

    /**
     * The cached name carries a digest of bucket and key, so replacing the object behind a profile
     * cannot be served from the file downloaded for the previous one.
     */
    private Path secretCacheFile(Path root, Long profileId, String kind, String bucket, String objectKey) {
        String fileName = kind + "-" + this.digestOf(bucket + "/" + objectKey) + this.extensionOf(objectKey);
        if (profileId == null) {
            return root.resolve("unsaved").resolve(UUID.randomUUID().toString()).resolve(fileName);
        }
        return root.resolve(String.valueOf(profileId)).resolve(fileName);
    }

    private String digestOf(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Could not derive a cache name for the Kafka store.", ex);
        }
    }

    private void createPrivateDirectories(Path dir) throws IOException {
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException ex) {
            // Non-POSIX filesystem: creating it is the most this can do.
            Files.createDirectories(dir);
        }
    }

    /**
     * The file is created owner-only before a byte of key material goes into it, rather than
     * copied first and tightened afterwards.
     */
    private void writePrivateFile(InputStream source, Path target) throws IOException {
        Files.deleteIfExists(target);
        try {
            Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ex) {
            Files.createFile(target);
        }
        try (InputStream in = source; OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }

    private String extensionOf(String objectKey) {
        int dot = objectKey.lastIndexOf('.');
        return dot >= 0 ? objectKey.substring(dot) : "";
    }

    /**
     * The JAAS entry is read back with a tokenizer that treats a quote as the end of the value and
     * a backslash as an escape, so an unescaped one in a username or password lets the rest of the
     * entry be written by whoever typed it. Escaping keeps a password that legitimately contains
     * either of them working instead of rejecting the profile outright.
     *
     * A line break ends the quoted value for that tokenizer just as a quote does, so it goes the
     * same way -- as the escape the tokenizer reads back as the break itself, which is what keeps
     * the promise above for a password that contains one.
     */
    private String jaasEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * Names that do not look like security settings and decide as much as any of them.
     * bootstrap.servers is the destination the profile's credentials are presented to -- the column
     * is validated and shown, a property restating it is neither. The two class-name properties are
     * instantiated by the client as it is built, and a text box does not get to name a class for
     * this JVM to load.
     */
    private static final Set<String> DENIED_CLIENT_PROPERTIES = Collections.unmodifiableSet(
        new HashSet<>(Arrays.asList("bootstrap.servers", "metric.reporters", "interceptor.classes")));

    /**
     * Whether a client property decides where we connect, how we authenticate or whether the wire is
     * encrypted. These are computed from the profile and are not for the Advanced box to restate: a
     * caller who could set sasl.jaas.config would be naming a login module for this JVM to
     * instantiate, and one who could set bootstrap.servers would be choosing who receives the
     * password behind a profile that still displays the broker it was approved for.
     */
    static boolean isReservedClientProperty(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("security.") || normalized.startsWith("sasl.")
            || normalized.startsWith("ssl.") || DENIED_CLIENT_PROPERTIES.contains(normalized);
    }

    private void deleteQuietlyRecursive(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    this.logger.warn("Could not delete cached secret file {}: {}", p, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            this.logger.warn("Could not walk cached secret dir {}: {}", dir, ex.getMessage());
        }
    }

    private void mergeAdditionalProperties(Map<String, Object> props, String additionalPropertiesJson) {
        if (additionalPropertiesJson == null || additionalPropertiesJson.trim().isEmpty()) {
            return;
        }
        try {
            Map<String, String> extra = this.gson.fromJson(additionalPropertiesJson, new TypeToken<Map<String, String>>() {}.getType());
            if (extra == null) {
                return;
            }
            List<String> rejected = new ArrayList<>();
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                if (isReservedClientProperty(entry.getKey())) {
                    rejected.add(entry.getKey());
                    continue;
                }
                props.put(entry.getKey(), entry.getValue());
            }
            if (!rejected.isEmpty()) {
                // Names only; the value alongside one of these is very often a credential.
                this.logger.warn("Ignoring reserved Kafka client properties in additionalProperties: {}", rejected);
            }
        } catch (Exception ex) {
            this.logger.warn("Could not parse additionalProperties JSON, ignoring: {}", ex.getMessage());
        }
    }

    public Map<String, Object> defaultAdminProps() {
        return new HashMap<>(this.kafkaProperties.buildAdminProperties());
    }

    public void ensureTopicExists(Optional<KafkaConnectionProfile> profile, String topic, int partitions) {
        if (topic == null || topic.trim().isEmpty()) {
            return;
        }
        try {
            // Building the client properties can fail on its own -- an undecryptable password, a
            // store that will not download -- and that is as much a provisioning failure as a
            // broker that will not answer, so it is handled here rather than thrown at the caller.
            Map<String, Object> adminProps = profile.map(this::commonClientProps).orElseGet(this::defaultAdminProps);
            try (AdminClient adminClient = AdminClient.create(adminProps)) {
                Set<String> existingTopics = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);
                if (existingTopics.contains(topic)) {
                    return;
                }
                adminClient.createTopics(Collections.singleton(new NewTopic(topic, partitions, this.defaultReplicationFactor)))
                    .all().get(10, TimeUnit.SECONDS);
                this.logger.info("Auto-created Kafka topic '{}' with {} partition(s).", topic, partitions);
            }
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof TopicExistsException) {

                return;
            }
            this.logger.warn("Could not auto-create Kafka topic '{}': {}", topic, ex.getMessage());
        } catch (Exception ex) {
            this.logger.warn("Could not auto-create Kafka topic '{}': {}", topic, ex.getMessage());
        }
    }

    /**
     * The one place a producer's durability is decided. KafkaProducerConfig builds the fallback
     * template through here too, so a job does not quietly change its retry behaviour depending on
     * whether a connection profile happened to resolve for it.
     */
    static Map<String, Object> applyProducerDefaults(Map<String, Object> props) {
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 500);
        return props;
    }

    private Map<String, Object> producerProps(KafkaConnectionProfile profile) {
        return applyProducerDefaults(this.commonClientProps(profile));
    }

    private static class CachedProducer {
        private final DefaultKafkaProducerFactory<String, String> factory;
        private final KafkaTemplate<String, String> template;

        private CachedProducer(DefaultKafkaProducerFactory<String, String> factory, KafkaTemplate<String, String> template) {
            this.factory = factory;
            this.template = template;
        }
    }

}
