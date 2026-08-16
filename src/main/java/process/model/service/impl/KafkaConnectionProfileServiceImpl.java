package process.model.service.impl;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SslAuthenticationException;
import org.apache.kafka.common.errors.TimeoutException;
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
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import process.model.service.KafkaConnectionProfileService;
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

@Service
public class KafkaConnectionProfileServiceImpl implements KafkaConnectionProfileService {

    private final Logger logger = LoggerFactory.getLogger(KafkaConnectionProfileServiceImpl.class);

    private static final List<String> VALID_SECURITY_PROTOCOLS =
        Arrays.asList("PLAINTEXT", "SASL_PLAINTEXT", "SASL_SSL", "SSL");

    private final KafkaConnectionProfileRepository profileRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final TenantTaskTypeKafkaRouteRepository routeRepository;
    private final EncryptionUtil encryptionUtil;
    private final KafkaTemplateProvider kafkaTemplateProvider;
    private final KafkaConnectionResolver kafkaConnectionResolver;

    public KafkaConnectionProfileServiceImpl(KafkaConnectionProfileRepository profileRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        TenantTaskTypeKafkaRouteRepository routeRepository,
        EncryptionUtil encryptionUtil, KafkaTemplateProvider kafkaTemplateProvider,
        KafkaConnectionResolver kafkaConnectionResolver) {
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
        ResponseDto validationError = this.validateProfile(dto);
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
        ResponseDto validationError = this.validateProfile(dto);
        if (validationError != null) {
            return validationError;
        }
        Optional<KafkaConnectionProfile> profileOpt = this.scopedFind(dto.getKafkaConnectionProfileId());
        if (!profileOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Profile not found with %d.", dto.getKafkaConnectionProfileId()));
        }
        KafkaConnectionProfile profile = profileOpt.get();
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

        boolean stillReferenced = this.sourceTaskTypeRepository.existsByKafkaConnectionProfileId(kafkaConnectionProfileId)
            || this.routeRepository.existsByKafkaConnectionProfileId(kafkaConnectionProfileId);
        if (stillReferenced) {
            return new ResponseDto(ERROR, "This profile is still used by a Source Task Type or tenant routing override -- reassign those first.");
        }
        KafkaConnectionProfile profile = profileOpt.get();
        profile.setStatus(Status.Delete);
        profile.setIsDefault(false);
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
        KafkaConnectionProfile profile;
        boolean persist = false;

        if (!isNull(dto.getKafkaConnectionProfileId())) {
            Optional<KafkaConnectionProfile> profileOpt = this.scopedFindReadOnly(dto.getKafkaConnectionProfileId());
            if (!profileOpt.isPresent()) {
                return new ResponseDto(ERROR, String.format("Profile not found with %d.", dto.getKafkaConnectionProfileId()));
            }
            profile = profileOpt.get();
            persist = true;
        } else {
            ResponseDto validationError = this.validateProfile(dto);
            if (validationError != null) {
                return validationError;
            }
            profile = new KafkaConnectionProfile();
            this.applyProfileDto(profile, dto);
        }
        ResponseDto result = this.doTestConnection(profile);
        if (persist) {
            profile.setConnectionStatus(result.getStatus().equals(SUCCESS) ? "SUCCESS" : "FAILED");
            profile.setLastTestedAt(new Timestamp(System.currentTimeMillis()));
            profile.setLastTestMessage(result.getMessage());
            this.profileRepository.save(profile);
        }
        return result;
    }

    private ResponseDto doTestConnection(KafkaConnectionProfile profile) {
        try (AdminClient adminClient = AdminClient.create(this.kafkaTemplateProvider.commonClientProps(profile))) {
            DescribeClusterResult result = adminClient.describeCluster();
            String clusterId = result.clusterId().get(10, TimeUnit.SECONDS);
            int nodeCount = result.nodes().get(10, TimeUnit.SECONDS).size();
            return new ResponseDto(SUCCESS, String.format(
                "Connected successfully -- cluster \"%s\" with %d broker(s).", clusterId, nodeCount));
        } catch (Exception ex) {
            this.logger.warn("Kafka test connection failed for '{}': {}", profile.getProfileName(), ex.getMessage());
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            String reason;
            if (cause instanceof SaslAuthenticationException) {
                reason = "Authentication rejected -- check username/password/mechanism.";
            } else if (cause instanceof SslAuthenticationException) {
                reason = "TLS handshake failed -- check certificates/truststore.";
            } else if (cause instanceof TimeoutException || ex instanceof TimeoutException) {
                reason = "Broker unreachable or network blocked (timed out).";
            } else {
                reason = cause.getMessage() != null ? cause.getMessage() : ex.getMessage();
            }
            return new ResponseDto(ERROR, "Could not connect: " + reason);
        }
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
            this.logger.warn("Kafka topic test failed for '{}': {}", topicName, ex.getMessage());
            return new ResponseDto(ERROR, "Could not reach topic: " + ex.getMessage());
        }
    }

    private ResponseDto validateProfile(KafkaConnectionProfileDto dto) {
        if (isNull(dto.getProfileName()) || dto.getProfileName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Profile name missing.");
        }
        if (isNull(dto.getBootstrapServers()) || dto.getBootstrapServers().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Bootstrap servers missing.");
        }
        if (isNull(dto.getSecurityProtocol()) || !VALID_SECURITY_PROTOCOLS.contains(dto.getSecurityProtocol())) {
            return new ResponseDto(ERROR, "securityProtocol must be one of " + VALID_SECURITY_PROTOCOLS + ".");
        }
        if (dto.getSecurityProtocol().startsWith("SASL_")) {
            if (isNull(dto.getSaslMechanism()) || dto.getSaslMechanism().trim().isEmpty()) {
                return new ResponseDto(ERROR, "saslMechanism is required for " + dto.getSecurityProtocol() + ".");
            }
            if (isNull(dto.getSaslUsername()) || dto.getSaslUsername().trim().isEmpty()) {
                return new ResponseDto(ERROR, "saslUsername is required for " + dto.getSecurityProtocol() + ".");
            }
        }
        if ("SSL".equals(dto.getSecurityProtocol()) || "SASL_SSL".equals(dto.getSecurityProtocol())) {
            if (isNull(dto.getSslTruststoreLocation()) || dto.getSslTruststoreLocation().trim().isEmpty()) {
                return new ResponseDto(ERROR, "sslTruststoreLocation is required for " + dto.getSecurityProtocol() + ".");
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

    private Optional<KafkaConnectionProfile> scopedFindReadOnly(Long kafkaConnectionProfileId) {
        Optional<KafkaConnectionProfile> profileOpt = this.profileRepository.findById(kafkaConnectionProfileId);
        if (!profileOpt.isPresent() || profileOpt.get().getStatus() == Status.Delete) {
            return Optional.empty();
        }
        if (TenantContext.isPlatformAdmin()) {
            return profileOpt;
        }
        Long ownerTenantId = profileOpt.get().getTenantId();
        if (ownerTenantId != null && !ownerTenantId.equals(TenantContext.getTenantId())) {
            return Optional.empty();
        }
        return profileOpt;
    }

    private void applyProfileDto(KafkaConnectionProfile profile, KafkaConnectionProfileDto dto) {
        profile.setProfileName(dto.getProfileName());
        profile.setEnvironmentLabel(dto.getEnvironmentLabel());
        profile.setBootstrapServers(dto.getBootstrapServers());
        profile.setSecurityProtocol(dto.getSecurityProtocol());
        profile.setSaslMechanism(dto.getSaslMechanism());
        profile.setSaslUsername(dto.getSaslUsername());
        if (!isNull(dto.getSaslPassword()) && !dto.getSaslPassword().trim().isEmpty()) {
            profile.setSaslPassword(this.encryptionUtil.encrypt(dto.getSaslPassword()));
        }
        profile.setSslKeystoreBucket(dto.getSslKeystoreBucket());
        profile.setSslKeystoreLocation(dto.getSslKeystoreLocation());
        if (!isNull(dto.getSslKeystorePassword()) && !dto.getSslKeystorePassword().trim().isEmpty()) {
            profile.setSslKeystorePasswordEnc(this.encryptionUtil.encrypt(dto.getSslKeystorePassword()));
        }
        if (!isNull(dto.getSslKeyPassword()) && !dto.getSslKeyPassword().trim().isEmpty()) {
            profile.setSslKeyPasswordEnc(this.encryptionUtil.encrypt(dto.getSslKeyPassword()));
        }
        profile.setSslTruststoreBucket(dto.getSslTruststoreBucket());
        profile.setSslTruststoreLocation(dto.getSslTruststoreLocation());
        if (!isNull(dto.getSslTruststorePassword()) && !dto.getSslTruststorePassword().trim().isEmpty()) {
            profile.setSslTruststorePasswordEnc(this.encryptionUtil.encrypt(dto.getSslTruststorePassword()));
        }
        profile.setSslEndpointIdentificationAlgorithm(dto.getSslEndpointIdentificationAlgorithm());
        profile.setAdditionalProperties(dto.getAdditionalProperties());
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
        dto.setSslKeystoreBucket(profile.getSslKeystoreBucket());
        dto.setSslKeystoreLocation(profile.getSslKeystoreLocation());
        dto.setSslKeystorePasswordConfigured(!isNull(profile.getSslKeystorePasswordEnc()));
        dto.setSslKeyPasswordConfigured(!isNull(profile.getSslKeyPasswordEnc()));
        dto.setSslTruststoreBucket(profile.getSslTruststoreBucket());
        dto.setSslTruststoreLocation(profile.getSslTruststoreLocation());
        dto.setSslTruststorePasswordConfigured(!isNull(profile.getSslTruststorePasswordEnc()));
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
