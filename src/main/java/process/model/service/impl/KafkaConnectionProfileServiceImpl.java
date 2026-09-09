package process.model.service.impl;

import org.apache.kafka.clients.admin.AdminClient;
import process.util.UserNameResolver;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.StorageConnection;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaConnectionProfileService;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class KafkaConnectionProfileServiceImpl implements KafkaConnectionProfileService {

    private final Logger logger = LoggerFactory.getLogger(KafkaConnectionProfileServiceImpl.class);

    private static final List<String> VALID_SECURITY_PROTOCOLS =
        Arrays.asList("PLAINTEXT", "SASL_PLAINTEXT", "SASL_SSL", "SSL");

    // KafkaTemplateProvider only knows how to build a JAAS entry for these three; anything else was
    // stored happily and then handed to the broker with the wrong login module.
    private static final List<String> VALID_SASL_MECHANISMS =
        Arrays.asList("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    private final KafkaConnectionProfileRepository profileRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final TenantTaskTypeKafkaRouteRepository routeRepository;
    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    private final UserNameResolver userNameResolver;
    private final KafkaSecretService kafkaSecretService;
    private final StorageConnectionRepository storageConnectionRepository;


    public KafkaConnectionProfileServiceImpl(KafkaConnectionProfileRepository profileRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        TenantTaskTypeKafkaRouteRepository routeRepository,
        EncryptionUtil encryptionUtil, KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver,
        UserNameResolver userNameResolver, KafkaSecretService kafkaSecretService,
        StorageConnectionRepository storageConnectionRepository) {
        this.userNameResolver = userNameResolver;
        this.kafkaSecretService = kafkaSecretService;
        this.storageConnectionRepository = storageConnectionRepository;
        this.profileRepository = profileRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.routeRepository = routeRepository;
        this.encryptionUtil = encryptionUtil;
        this.kafkaTemplateProvider = kafkaTemplateProvider;
        this.kafkaConnectionResolver = kafkaConnectionResolver;
    }

    @Override
    @Transactional
    public ResponseDto addProfile(KafkaConnectionProfileDto dto) throws Exception {
        ResponseDto validationError = this.validateProfile(dto, null);
        if (validationError == null) {
            validationError = this.validateSecretReferences(dto, null);
        }
        if (validationError != null) {
            return validationError;
        }
        KafkaConnectionProfile profile = new KafkaConnectionProfile();

        profile.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
        this.applyProfileDto(profile, dto);
        profile.setStatus(Status.Active);
        profile.setIsDefault(false);
        profile.setConnectionStatus("UNTESTED");
        profile.setDateCreated(new Timestamp(System.currentTimeMillis()));
        profile = this.profileRepository.save(profile);
        return new ResponseDto(SUCCESS, String.format("Kafka connection profile saved with %d.",
            profile.getKafkaConnectionProfileId()), this.getProfileDto(profile));
    }

    @Override
    public ResponseDto updateProfile(KafkaConnectionProfileDto dto) throws Exception {
        if (isNull(dto.getKafkaConnectionProfileId())) {
            return new ResponseDto(ERROR, "Kafka connection profile id missing.");
        }
        Optional<KafkaConnectionProfile> profileOpt = this.scopedFind(dto.getKafkaConnectionProfileId());
        if (!profileOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Profile not found with %d.", dto.getKafkaConnectionProfileId()));
        }
        KafkaConnectionProfile profile = profileOpt.get();
        // The row has to be in hand before the payload can be judged: a blank password on an edit is
        // "keep the stored one", so whether one is present at all depends on what is already there.
        ResponseDto validationError = this.validateProfile(dto, profile.getSaslPassword());
        if (validationError == null) {
            validationError = this.validateSecretReferences(dto, profile);
        }
        if (validationError == null) {
            validationError = this.refuseMovedCredential(profile, dto);
        }
        if (validationError != null) {
            return validationError;
        }
        if (!isNull(dto.getStatus())) {
            if (dto.getStatus() == Status.Delete) {
                return new ResponseDto(ERROR, "Use delete to remove a profile; status can only be Active or Inactive.");
            }
            profile.setStatus(dto.getStatus());
            if (dto.getStatus() != Status.Active) {
                profile.setIsDefault(false);
            }
        }
        this.applyProfileDto(profile, dto);
        this.profileRepository.save(profile);

        this.kafkaTemplateProvider.invalidate(profile.getKafkaConnectionProfileId());
        return new ResponseDto(SUCCESS, String.format("Kafka connection profile saved with %d.",
            profile.getKafkaConnectionProfileId()));
    }

    @Override
    public ResponseDto deleteProfile(Long kafkaConnectionProfileId) throws Exception {
        if (isNull(kafkaConnectionProfileId)) {
            return new ResponseDto(ERROR, "Kafka connection profile id missing.");
        }
        Optional<KafkaConnectionProfile> profileOpt = this.scopedFind(kafkaConnectionProfileId);
        if (!profileOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Profile not found with %d.", kafkaConnectionProfileId));
        }

        // A deleted task type keeps its kafka_connection_profile_id, so the reference has to be read
        // as "a task type that still exists uses this". Counting the soft-deleted ones made the
        // profile permanently undeletable the moment the last task type using it was deleted: the
        // operator was told to reassign a task type that no screen lists any more. The routes are
        // hard-deleted, so their check needs no such qualification.
        boolean stillReferenced = this.sourceTaskTypeRepository
                .existsByKafkaConnectionProfileIdAndStatusNot(kafkaConnectionProfileId, Status.Delete)
            || this.routeRepository.existsByKafkaConnectionProfileId(kafkaConnectionProfileId);
        if (stillReferenced) {
            return new ResponseDto(ERROR, "This profile is still used by a Source Task Type or tenant routing override -- reassign those first.");
        }
        KafkaConnectionProfile profile = profileOpt.get();
        profile.setStatus(Status.Delete);
        profile.setIsDefault(false);
        // Nothing brings a deleted profile back, so its credentials have no reason to sit in the table
        // waiting for a database dump -- the row stays for the audit trail, the secrets do not.
        profile.setSaslPassword(null);
        profile.setSslKeystorePasswordEnc(null);
        profile.setSslKeyPasswordEnc(null);
        profile.setSslTruststorePasswordEnc(null);
        this.profileRepository.save(profile);
        this.kafkaTemplateProvider.invalidate(kafkaConnectionProfileId);
        return new ResponseDto(SUCCESS, String.format("Kafka connection profile deleted with %d.", kafkaConnectionProfileId));
    }

    @Override
    public ResponseDto fetchAllProfiles() throws Exception {
        List<KafkaConnectionProfile> visible = TenantContext.isPlatformAdmin()
            ? this.profileRepository.findVisibleToPlatformAdmin(Status.Delete)
            : this.profileRepository.findVisibleToTenant(TenantContext.getTenantId(), Status.Delete);
        List<KafkaConnectionProfileDto> profiles = visible.stream().map(this::getProfileDto).collect(Collectors.toList());
        // One lookup for the page rather than one per row.
        this.userNameResolver.attachToDtos(profiles, this.profileRepository,
            KafkaConnectionProfile::getKafkaConnectionProfileId);
        return new ResponseDto(SUCCESS, "Kafka connection profiles fetched successfully.", profiles);
    }

    @Override
    public ResponseDto setAsDefault(Long kafkaConnectionProfileId) throws Exception {
        if (isNull(kafkaConnectionProfileId)) {
            return new ResponseDto(ERROR, "Kafka connection profile id missing.");
        }
        Optional<KafkaConnectionProfile> profileOpt = this.scopedFind(kafkaConnectionProfileId);
        if (!profileOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Profile not found with %d.", kafkaConnectionProfileId));
        }
        KafkaConnectionProfile profile = profileOpt.get();
        if (profile.getStatus() != Status.Active) {
            return new ResponseDto(ERROR, "This profile is not active and cannot be selected as the default.");
        }

        if (profile.getTenantId() == null) {
            this.profileRepository.clearPlatformDefaultExcept(kafkaConnectionProfileId);
        } else {
            this.profileRepository.clearDefaultForTenantExcept(profile.getTenantId(), kafkaConnectionProfileId);
        }
        profile.setIsDefault(true);
        this.profileRepository.save(profile);
        this.kafkaTemplateProvider.invalidate(kafkaConnectionProfileId);
        return new ResponseDto(SUCCESS, String.format("\"%s\" is now the default Kafka connection.", profile.getProfileName()));
    }

    @Override
    public ResponseDto clearDefault() throws Exception {
        if (TenantContext.isPlatformAdmin()) {
            this.profileRepository.clearPlatformDefault();
        } else {
            this.profileRepository.clearDefaultForTenant(TenantContext.getTenantId());
        }
        return new ResponseDto(SUCCESS, "Reverted to the next fallback Kafka connection.");
    }

    @Override
    public ResponseDto testConnection(KafkaConnectionProfileDto dto) throws Exception {
        KafkaConnectionProfile stored = null;
        KafkaConnectionProfile probe;
        boolean persist = false;
        boolean materialChanged = false;

        if (!isNull(dto.getKafkaConnectionProfileId())) {
            // Own profiles only, like the listing. Reaching a platform profile here would let a
            // tenant open a connection to the platform's broker using the platform's stored
            // credentials, by guessing an id it can no longer see.
            Optional<KafkaConnectionProfile> profileOpt = this.scopedFind(dto.getKafkaConnectionProfileId());
            if (!profileOpt.isPresent()) {
                return new ResponseDto(ERROR, String.format("Profile not found with %d.", dto.getKafkaConnectionProfileId()));
            }
            stored = profileOpt.get();
            // Defence in depth, and unreachable as things stand: scopedFind above already refuses
            // any row the caller does not own to anyone but a platform admin, and callerOwns is
            // true for a platform admin, so this is never false today. It dates from when the
            // platform's profiles were listed to every tenant, where a tenant could reach a shared
            // row and recording its result would have let one tenant write what every tenant
            // reads. Kept because it is the rule that makes the write safe if that listing is ever
            // widened again -- not because anything currently depends on it.
            persist = this.callerOwns(stored);
            probe = this.copyForProbe(stored);
            if (persist && !isNull(dto.getBootstrapServers()) && !dto.getBootstrapServers().trim().isEmpty()) {
                // "Test connection" sits beside the unsaved edits in the dialog, so it has to probe what
                // the operator is about to save rather than the row as it still stands. The copy keeps the
                // stored ciphertext, so a blank password field still means "the one already saved" -- for
                // as long as the edit leaves the credential where it was; refuseMovedCredential decides that.
                ResponseDto validationError = this.validateProfile(dto, stored.getSaslPassword());
                if (validationError == null) {
                    // The third path that puts a bucket and key onto a profile, and it needs the same
                    // check as the two that save one: the probe is handed to the Kafka client, which
                    // fetches TLS material through the storage layer's trusted read -- the read that
                    // asks no questions precisely because the reference was supposed to be verified
                    // here. Without this, a test request names any object it likes.
                    validationError = this.validateSecretReferences(dto, stored);
                }
                if (validationError == null) {
                    validationError = this.refuseMovedCredential(stored, dto);
                }
                if (validationError != null) {
                    return validationError;
                }
                this.applyProfileDto(probe, dto);
                materialChanged = this.sslMaterialChanged(stored, dto);
            }
        } else {
            ResponseDto validationError = this.validateProfile(dto, null);
            if (validationError == null) {
                // No stored row to grandfather an unchanged reference against, so every reference in
                // an unsaved profile is checked.
                validationError = this.validateSecretReferences(dto, null);
            }
            if (validationError != null) {
                return validationError;
            }
            probe = new KafkaConnectionProfile();
            this.applyProfileDto(probe, dto);
        }
        ResponseDto result = this.runProbe(probe, materialChanged);
        if (persist) {
            stored.setConnectionStatus(result.getStatus().equals(SUCCESS) ? "SUCCESS" : "FAILED");
            stored.setLastTestedAt(new Timestamp(System.currentTimeMillis()));
            stored.setLastTestMessage(result.getMessage());
            this.profileRepository.save(stored);
        }
        return result;
    }

    /**
     * The template provider caches keystore/truststore material on disk under the profile id, so a
     * test that points the profile at a different store has to start from a clean cache and must not
     * leave the unsaved edit's download behind for the next dispatch to pick up.
     */
    private ResponseDto runProbe(KafkaConnectionProfile probe, boolean materialChanged) {
        if (!materialChanged) {
            return this.doTestConnection(probe);
        }
        this.kafkaTemplateProvider.invalidate(probe.getKafkaConnectionProfileId());
        try {
            return this.doTestConnection(probe);
        } finally {
            this.kafkaTemplateProvider.invalidate(probe.getKafkaConnectionProfileId());
        }
    }

    /** True when the test payload points the profile at a different keystore or truststore. */
    private boolean sslMaterialChanged(KafkaConnectionProfile stored, KafkaConnectionProfileDto dto) {
        return !this.sameText(stored.getSslKeystoreBucket(), dto.getSslKeystoreBucket())
            || !this.sameText(stored.getSslKeystoreLocation(), dto.getSslKeystoreLocation())
            || !this.sameText(stored.getSslTruststoreBucket(), dto.getSslTruststoreBucket())
            || !this.sameText(stored.getSslTruststoreLocation(), dto.getSslTruststoreLocation());
    }

    private boolean sameText(String stored, String incoming) {
        String left = isNull(stored) ? "" : stored.trim();
        String right = isNull(incoming) ? "" : incoming.trim();
        return left.equals(right);
    }

    private ResponseDto doTestConnection(KafkaConnectionProfile profile) {
        try (AdminClient adminClient = AdminClient.create(this.kafkaTemplateProvider.commonClientProps(profile))) {
            DescribeClusterResult result = adminClient.describeCluster();
            String clusterId = result.clusterId().get(10, TimeUnit.SECONDS);
            int nodeCount = result.nodes().get(10, TimeUnit.SECONDS).size();
            return new ResponseDto(SUCCESS, String.format(
                "Connected successfully -- cluster \"%s\" with %d broker(s).", clusterId, nodeCount));
        } catch (Exception ex) {
            // The caller is told only that it failed, so the log is now the only place the reason exists.
            this.logger.warn("Kafka test connection failed for '{}': {}", profile.getProfileName(), ex.getMessage(), ex);
            return new ResponseDto(ERROR, "Could not connect: " + this.failureReason(ex));
        }
    }

    /**
     * What a caller is told when a Kafka client fails, which is as little as still helps them fix it.
     * The client's own text names hosts and resolver outcomes, which turns these endpoints into a map
     * of the network the server sits in -- and the topic test runs against whichever profile resolved
     * for the tenant, which may be the platform's own, a row that tenant is not even shown. It stays
     * in the log for whoever runs the server.
     */
    private String failureReason(Exception ex) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof SaslAuthenticationException) {
            return "Authentication rejected -- check username/password/mechanism.";
        }
        if (cause instanceof SslAuthenticationException) {
            return "TLS handshake failed -- check certificates/truststore.";
        }
        if (cause instanceof TimeoutException || ex instanceof TimeoutException) {
            return "Broker unreachable or network blocked (timed out).";
        }
        return "The broker did not answer -- check the bootstrap servers and security settings; "
            + "the detail is in the server log.";
    }

    @Override
    public ResponseDto testTopicConnection(String topicName) throws Exception {
        if (isNull(topicName) || topicName.trim().isEmpty()) {
            return new ResponseDto(ERROR, "Topic name missing.");
        }
        Long tenantId = TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId();
        Optional<KafkaConnectionProfile> resolved = this.kafkaConnectionResolver.resolve(tenantId, null);
        Map<String, Object> adminProps = resolved.map(this.kafkaTemplateProvider::commonClientProps)
            .orElseGet(this.kafkaTemplateProvider::defaultAdminProps);
        try (AdminClient adminClient = AdminClient.create(adminProps)) {
            DescribeTopicsResult result = adminClient.describeTopics(Collections.singleton(topicName));
            TopicDescription description = result.values().get(topicName).get(10, TimeUnit.SECONDS);
            return new ResponseDto(SUCCESS, String.format(
                "Topic \"%s\" is reachable -- %d partition(s).", topicName, description.partitions().size()));
        } catch (Exception ex) {
            this.logger.warn("Kafka topic test failed for '{}': {}", topicName, ex.getMessage(), ex);
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            // The one answer that is about the topic the caller named rather than about the cluster
            // behind it, and the whole reason the button exists.
            if (cause instanceof UnknownTopicOrPartitionException) {
                return new ResponseDto(ERROR, String.format("Topic \"%s\" does not exist on the connected cluster.", topicName));
            }
            return new ResponseDto(ERROR, "Could not reach topic: " + this.failureReason(ex));
        }
    }

    /**
     * storedSaslPassword is the ciphertext already on the row, or null when there is no row yet -- a
     * blank password field on an edit means "keep that one", so it decides whether one exists at all.
     */
    private ResponseDto validateProfile(KafkaConnectionProfileDto dto, String storedSaslPassword) {
        if (isNull(dto.getProfileName()) || dto.getProfileName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Profile name missing.");
        }
        if (isNull(dto.getBootstrapServers()) || dto.getBootstrapServers().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Bootstrap servers missing.");
        }
        if (!this.isWellFormedBootstrapServers(dto.getBootstrapServers())) {
            return new ResponseDto(ERROR, "bootstrapServers must be a comma-separated list of host:port pairs.");
        }
        if (isNull(dto.getSecurityProtocol()) || !VALID_SECURITY_PROTOCOLS.contains(dto.getSecurityProtocol())) {
            return new ResponseDto(ERROR, "securityProtocol must be one of " + VALID_SECURITY_PROTOCOLS + ".");
        }
        if (dto.getSecurityProtocol().startsWith("SASL_")) {
            if (isNull(dto.getSaslMechanism()) || !VALID_SASL_MECHANISMS.contains(dto.getSaslMechanism())) {
                return new ResponseDto(ERROR, "saslMechanism must be one of " + VALID_SASL_MECHANISMS + ".");
            }
            if (isNull(dto.getSaslUsername()) || dto.getSaslUsername().trim().isEmpty()) {
                return new ResponseDto(ERROR, "saslUsername is required for " + dto.getSecurityProtocol() + ".");
            }
            boolean passwordSupplied = !isNull(dto.getSaslPassword()) && !dto.getSaslPassword().trim().isEmpty();
            boolean passwordKept = !isNull(storedSaslPassword) && !Boolean.TRUE.equals(dto.getClearSaslPassword());
            if (!passwordSupplied && !passwordKept) {
                return new ResponseDto(ERROR, "saslPassword is required for " + dto.getSecurityProtocol() + ".");
            }
        }
        if ("SSL".equals(dto.getSecurityProtocol()) || "SASL_SSL".equals(dto.getSecurityProtocol())) {
            // A truststore is optional: a broker whose certificate chains to a well-known CA is already
            // covered by the JVM's own truststore, which is what the dialog's guide tells the operator.
            // Only its password without it is meaningless, and that is worth saying out loud.
            boolean truststoreNamed = !isNull(dto.getSslTruststoreLocation())
                && !dto.getSslTruststoreLocation().trim().isEmpty();
            boolean truststorePasswordGiven = !isNull(dto.getSslTruststorePassword())
                && !dto.getSslTruststorePassword().trim().isEmpty();
            if (truststorePasswordGiven && !truststoreNamed) {
                return new ResponseDto(ERROR, "sslTruststorePassword needs an sslTruststoreLocation -- a truststore "
                    + "itself is optional, leave both blank to use the JVM default for brokers signed by a well-known CA.");
            }
        }
        if (!isNull(dto.getAdditionalProperties()) && !dto.getAdditionalProperties().trim().isEmpty()) {
            try {
                new Gson().fromJson(dto.getAdditionalProperties(),
                    new TypeToken<Map<String, String>>() {}.getType());
            } catch (Exception ex) {
                return new ResponseDto(ERROR, "additionalProperties must be a valid JSON object of string properties.");
            }
        }
        return null;
    }

    /**
     * Refuses a profile that points at TLS material the caller is not entitled to.
     *
     * This has to be checked when the row is saved, because it cannot be checked when the file is
     * read: the Kafka client is built on scheduler and startup threads with no principal, so the
     * read goes through the storage layer's trusted path and asks no questions. The bucket and key
     * on the row are therefore taken on trust later, which is only sound if they were verified
     * here first.
     *
     * An unchanged reference is left alone. Profiles predating the kafka-secrets layout hold keys
     * this cannot parse, and re-validating them on an unrelated edit would make those rows
     * impossible to save rather than making anything safer -- what matters is that a caller cannot
     * point a profile at something new that is not theirs.
     */
    private ResponseDto refuseUnusableSecret(String bucket, String location,
        String storedBucket, String storedLocation, String what) {
        if (isNull(bucket) || bucket.trim().isEmpty() || isNull(location) || location.trim().isEmpty()) {
            return null;
        }
        if (bucket.equals(storedBucket) && location.equals(storedLocation)) {
            return null;
        }
        if (KafkaSecretService.SECRET_BUCKET.equals(bucket)) {
            return this.kafkaSecretService.canUseObject(bucket, location) ? null
                : new ResponseDto(ERROR, String.format("That %s could not be found.", what));
        }
        // Any other bucket is a storage connection, and a tenant may only name one of its own --
        // otherwise a profile could borrow another tenant's alias and have the trusted read fetch
        // whatever sits at that key.
        Optional<StorageConnection> connection = this.storageConnectionRepository.findByAlias(bucket);
        if (!connection.isPresent()) {
            return new ResponseDto(ERROR, String.format("No storage connection is called '%s'.", bucket));
        }
        if (TenantContext.isPlatformAdmin()) {
            return null;
        }
        Long ownerTenantId = connection.get().getTenantId();
        if (ownerTenantId == null || !ownerTenantId.equals(TenantContext.getTenantId())) {
            return new ResponseDto(ERROR, String.format("No storage connection is called '%s'.", bucket));
        }
        return null;
    }

    private ResponseDto validateSecretReferences(KafkaConnectionProfileDto dto, KafkaConnectionProfile stored) {
        ResponseDto refused = this.refuseUnusableSecret(dto.getSslTruststoreBucket(), dto.getSslTruststoreLocation(),
            stored == null ? null : stored.getSslTruststoreBucket(),
            stored == null ? null : stored.getSslTruststoreLocation(), "truststore");
        if (refused != null) {
            return refused;
        }
        return this.refuseUnusableSecret(dto.getSslKeystoreBucket(), dto.getSslKeystoreLocation(),
            stored == null ? null : stored.getSslKeystoreBucket(),
            stored == null ? null : stored.getSslKeystoreLocation(), "keystore");
    }

    /**
     * Refuses to carry a stored SASL password to somewhere it was never sent.
     *
     * The password is write-only: a profile comes back saying whether one is set and never what it
     * is, and a blank field on an edit means "keep that one". Put together, those two conveniences
     * let a caller who has never seen the password decide who receives it -- point the profile at a
     * broker they run, leave the field blank, and the server authenticates to their listener with
     * it. PLAIN hands over the password itself; SCRAM hands over a proof of it that can be attacked
     * offline. Either way the write-only secret has been read.
     *
     * So the saved one is reused only where it already went: the same brokers, the same protocol,
     * the same mechanism, the same account. Moving any of those is an edit whoever is making it can
     * only make by typing the password again -- which is no obstacle to the operator who knows it,
     * and the whole obstacle to the one who does not.
     */
    private ResponseDto refuseMovedCredential(KafkaConnectionProfile stored, KafkaConnectionProfileDto dto) {
        if (isNull(stored.getSaslPassword())) {
            return null;
        }
        // Off SASL entirely the stored password is dropped rather than sent (applyProfileDto), so
        // there is nothing here to move.
        if (isNull(dto.getSecurityProtocol()) || !dto.getSecurityProtocol().startsWith("SASL_")) {
            return null;
        }
        if (!isNull(dto.getSaslPassword()) && !dto.getSaslPassword().trim().isEmpty()) {
            return null;
        }
        if (this.sameText(stored.getBootstrapServers(), dto.getBootstrapServers())
            && this.sameText(stored.getSecurityProtocol(), dto.getSecurityProtocol())
            && this.sameText(stored.getSaslMechanism(), dto.getSaslMechanism())
            && this.sameText(stored.getSaslUsername(), dto.getSaslUsername())) {
            return null;
        }
        return new ResponseDto(ERROR, "Enter the SASL password again -- a saved password is only reused for the "
            + "bootstrap servers, security protocol, mechanism and username it was saved with, and this changes one of those.");
    }

    private Optional<KafkaConnectionProfile> scopedFind(Long kafkaConnectionProfileId) {
        Optional<KafkaConnectionProfile> profileOpt = this.profileRepository.findById(kafkaConnectionProfileId);
        if (!profileOpt.isPresent() || profileOpt.get().getStatus() == Status.Delete) {
            return Optional.empty();
        }
        if (TenantContext.isPlatformAdmin()) {
            return profileOpt;
        }

        Long ownerTenantId = profileOpt.get().getTenantId();
        if (ownerTenantId == null || !ownerTenantId.equals(TenantContext.getTenantId())) {
            return Optional.empty();
        }
        return profileOpt;
    }

    /**
     * Ownership, kept separate from visibility because the two are not the same question even now
     * that a tenant only ever sees its own rows: a platform admin sees everything and owns nothing
     * in particular, so a write still has to ask this rather than assume the row was reachable.
     */
    private boolean callerOwns(KafkaConnectionProfile profile) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        Long ownerTenantId = profile.getTenantId();
        return ownerTenantId != null && ownerTenantId.equals(TenantContext.getTenantId());
    }

    /**
     * A detached stand-in for the stored row. The caller's unsaved edits are applied to this and never
     * to the row itself, so testing a profile cannot leave anything behind for a flush to pick up.
     */
    private KafkaConnectionProfile copyForProbe(KafkaConnectionProfile profile) {
        KafkaConnectionProfile copy = new KafkaConnectionProfile();
        copy.setKafkaConnectionProfileId(profile.getKafkaConnectionProfileId());
        copy.setTenantId(profile.getTenantId());
        copy.setProfileName(profile.getProfileName());
        copy.setEnvironmentLabel(profile.getEnvironmentLabel());
        copy.setBootstrapServers(profile.getBootstrapServers());
        copy.setSecurityProtocol(profile.getSecurityProtocol());
        copy.setSaslMechanism(profile.getSaslMechanism());
        copy.setSaslUsername(profile.getSaslUsername());
        copy.setSaslPassword(profile.getSaslPassword());
        copy.setSslKeystoreBucket(profile.getSslKeystoreBucket());
        copy.setSslKeystoreLocation(profile.getSslKeystoreLocation());
        copy.setSslKeystorePasswordEnc(profile.getSslKeystorePasswordEnc());
        copy.setSslKeyPasswordEnc(profile.getSslKeyPasswordEnc());
        copy.setSslTruststoreBucket(profile.getSslTruststoreBucket());
        copy.setSslTruststoreLocation(profile.getSslTruststoreLocation());
        copy.setSslTruststorePasswordEnc(profile.getSslTruststorePasswordEnc());
        copy.setSslEndpointIdentificationAlgorithm(profile.getSslEndpointIdentificationAlgorithm());
        copy.setAdditionalProperties(profile.getAdditionalProperties());
        return copy;
    }

    /**
     * Kafka only objects to a malformed bootstrap list once a client is being built, and the objection
     * names the host, so the shape is checked here where a plain message can be given instead.
     */
    private boolean isWellFormedBootstrapServers(String bootstrapServers) {
        String[] entries = bootstrapServers.split(",");
        if (entries.length == 0) {
            return false;
        }
        for (String entry : entries) {
            String trimmed = entry.trim();
            int portSeparator = trimmed.lastIndexOf(':');
            if (portSeparator <= 0 || portSeparator == trimmed.length() - 1) {
                return false;
            }
            // Bracketed IPv6 literals keep their own colons, so only the characters are checked here.
            if (!trimmed.substring(0, portSeparator).matches("[A-Za-z0-9._:\\[\\]-]+")) {
                return false;
            }
            try {
                int port = Integer.parseInt(trimmed.substring(portSeparator + 1));
                if (port < 1 || port > 65535) {
                    return false;
                }
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    private void applyProfileDto(KafkaConnectionProfile profile, KafkaConnectionProfileDto dto) {
        profile.setProfileName(dto.getProfileName());
        profile.setEnvironmentLabel(dto.getEnvironmentLabel());
        profile.setBootstrapServers(dto.getBootstrapServers());
        profile.setSecurityProtocol(dto.getSecurityProtocol());
        profile.setSaslMechanism(dto.getSaslMechanism());
        profile.setSaslUsername(dto.getSaslUsername());
        // A blank secret means "keep the stored one" -- the explicit clear flag is the only way back to
        // none at all, because nothing in the old flow ever put one of these columns to null again.
        if (!isNull(dto.getSaslPassword()) && !dto.getSaslPassword().trim().isEmpty()) {
            profile.setSaslPassword(this.encryptionUtil.encrypt(dto.getSaslPassword()));
        } else if (Boolean.TRUE.equals(dto.getClearSaslPassword())) {
            profile.setSaslPassword(null);
        }
        profile.setSslKeystoreBucket(dto.getSslKeystoreBucket());
        profile.setSslKeystoreLocation(dto.getSslKeystoreLocation());
        // A store this server generated comes back with its password already encrypted, because it
        // was built in an earlier request and must not travel in the clear between the two. That
        // value goes to the column as it stands; encrypting it again would store
        // encrypt(ciphertext) and the store would never open.
        if (!isNull(dto.getSslKeystorePasswordEnc()) && !dto.getSslKeystorePasswordEnc().trim().isEmpty()) {
            profile.setSslKeystorePasswordEnc(dto.getSslKeystorePasswordEnc().trim());
        } else if (!isNull(dto.getSslKeystorePassword()) && !dto.getSslKeystorePassword().trim().isEmpty()) {
            profile.setSslKeystorePasswordEnc(this.encryptionUtil.encrypt(dto.getSslKeystorePassword()));
        } else if (Boolean.TRUE.equals(dto.getClearSslKeystorePassword())) {
            profile.setSslKeystorePasswordEnc(null);
        }
        if (!isNull(dto.getSslKeyPasswordEnc()) && !dto.getSslKeyPasswordEnc().trim().isEmpty()) {
            profile.setSslKeyPasswordEnc(dto.getSslKeyPasswordEnc().trim());
        } else if (!isNull(dto.getSslKeyPassword()) && !dto.getSslKeyPassword().trim().isEmpty()) {
            profile.setSslKeyPasswordEnc(this.encryptionUtil.encrypt(dto.getSslKeyPassword()));
        } else if (Boolean.TRUE.equals(dto.getClearSslKeyPassword())) {
            profile.setSslKeyPasswordEnc(null);
        }
        profile.setSslTruststoreBucket(dto.getSslTruststoreBucket());
        profile.setSslTruststoreLocation(dto.getSslTruststoreLocation());
        if (!isNull(dto.getSslTruststorePasswordEnc()) && !dto.getSslTruststorePasswordEnc().trim().isEmpty()) {
            profile.setSslTruststorePasswordEnc(dto.getSslTruststorePasswordEnc().trim());
        } else if (!isNull(dto.getSslTruststorePassword()) && !dto.getSslTruststorePassword().trim().isEmpty()) {
            profile.setSslTruststorePasswordEnc(this.encryptionUtil.encrypt(dto.getSslTruststorePassword()));
        } else if (Boolean.TRUE.equals(dto.getClearSslTruststorePassword())) {
            profile.setSslTruststorePasswordEnc(null);
        }
        profile.setSslEndpointIdentificationAlgorithm(dto.getSslEndpointIdentificationAlgorithm());
        profile.setAdditionalProperties(dto.getAdditionalProperties());
        // A credential must not outlive the protocol that used it. Moving a profile off SASL cleared the
        // username on screen but left the encrypted password in the row with nothing able to reach it.
        String securityProtocol = profile.getSecurityProtocol();
        if (securityProtocol == null || !securityProtocol.startsWith("SASL_")) {
            profile.setSaslMechanism(null);
            profile.setSaslUsername(null);
            profile.setSaslPassword(null);
        }
        if (!"SSL".equals(securityProtocol) && !"SASL_SSL".equals(securityProtocol)) {
            // The stores go with their passwords, for the same reason the SASL branch above drops
            // the username alongside its password: half a credential is worse than none. Clearing
            // only the passwords left the row pointing at a PKCS12 whose password had been thrown
            // away, and the console read that surviving location as "keeping the truststore this
            // profile was saved with" -- so a profile switched to PLAINTEXT and back looked intact
            // on screen, saved without complaint, and failed at the handshake with no password to
            // open the store it still named. getProfileDto takes the same view of a location as of
            // a secret ("where the key material sits is as good as the key material"), so it is
            // consistent that it does not outlive the protocol either.
            profile.setSslKeystorePasswordEnc(null);
            profile.setSslKeyPasswordEnc(null);
            profile.setSslTruststorePasswordEnc(null);
            profile.setSslKeystoreBucket(null);
            profile.setSslKeystoreLocation(null);
            profile.setSslTruststoreBucket(null);
            profile.setSslTruststoreLocation(null);
        }
    }

    private KafkaConnectionProfileDto getProfileDto(KafkaConnectionProfile profile) {
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setKafkaConnectionProfileId(profile.getKafkaConnectionProfileId());
        dto.setTenantId(profile.getTenantId());
        dto.setProfileName(profile.getProfileName());
        dto.setEnvironmentLabel(profile.getEnvironmentLabel());
        dto.setBootstrapServers(profile.getBootstrapServers());
        dto.setSecurityProtocol(profile.getSecurityProtocol());
        dto.setSaslMechanism(profile.getSaslMechanism());
        dto.setSaslUsername(profile.getSaslUsername());
        dto.setSaslPasswordConfigured(!isNull(profile.getSaslPassword()));
        dto.setSslKeystorePasswordConfigured(!isNull(profile.getSslKeystorePasswordEnc()));
        dto.setSslKeyPasswordConfigured(!isNull(profile.getSslKeyPasswordEnc()));
        dto.setSslTruststorePasswordConfigured(!isNull(profile.getSslTruststorePasswordEnc()));
        // Where the key material sits is as good as the key material: the object browser hands the file
        // to anyone who can name the bucket and key, and a platform profile is listed to every tenant.
        // A caller who does not own the profile is never told where its stores live.
        if (this.callerOwns(profile)) {
            dto.setSslKeystoreBucket(profile.getSslKeystoreBucket());
            dto.setSslKeystoreLocation(profile.getSslKeystoreLocation());
            dto.setSslTruststoreBucket(profile.getSslTruststoreBucket());
            dto.setSslTruststoreLocation(profile.getSslTruststoreLocation());
        }
        dto.setSslEndpointIdentificationAlgorithm(profile.getSslEndpointIdentificationAlgorithm());
        dto.setAdditionalProperties(profile.getAdditionalProperties());
        dto.setIsDefault(profile.getIsDefault());
        dto.setStatus(profile.getStatus());

        dto.setConnectionStatus(!isNull(profile.getConnectionStatus()) ? profile.getConnectionStatus() : "UNTESTED");
        dto.setLastTestedAt(profile.getLastTestedAt());
        dto.setLastTestMessage(profile.getLastTestMessage());
        dto.setDateCreated(profile.getDateCreated());
        return dto;
    }

}
