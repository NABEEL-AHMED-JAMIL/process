package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.engine.query.DatabaseConnectionFactory;
import process.model.dto.DatabaseConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.service.ConnectionProfileService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

@Service
public class ConnectionProfileServiceImpl implements ConnectionProfileService {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionProfileServiceImpl.class);

    private final DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    private final QueryDefinitionRepository queryDefinitionRepository;
    private final EncryptionUtil encryptionUtil;
    private final TenantFilterHelper tenantFilterHelper;
    private final DatabaseConnectionFactory databaseConnectionFactory;

    @PersistenceContext
    private EntityManager entityManager;

    public ConnectionProfileServiceImpl(DatabaseConnectionProfileRepository databaseConnectionProfileRepository,
        QueryDefinitionRepository queryDefinitionRepository, EncryptionUtil encryptionUtil,
        TenantFilterHelper tenantFilterHelper, DatabaseConnectionFactory databaseConnectionFactory) {
        this.databaseConnectionProfileRepository = databaseConnectionProfileRepository;
        this.queryDefinitionRepository = queryDefinitionRepository;
        this.encryptionUtil = encryptionUtil;
        this.tenantFilterHelper = tenantFilterHelper;
        this.databaseConnectionFactory = databaseConnectionFactory;
    }

    private boolean isOwnedByCaller(DatabaseConnectionProfile profile) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return profile != null && Objects.equals(profile.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception {
        ResponseDto validationError = this.validate(dto, true);
        if (validationError != null) {
            return validationError;
        }

        if (isNull(TenantContext.getTenantId())) {
            return new ResponseDto(ERROR, "A platform admin can't own a database connection directly -- log in as a tenant user to create one.");
        }
        DatabaseConnectionProfile profile = new DatabaseConnectionProfile();
        profile.setTenantId(TenantContext.getTenantId());
        this.applyDto(profile, dto);
        profile.setStatus(Status.Active);
        profile.setCreatedBy(TenantContext.getAppUserId());
        profile.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        profile = this.databaseConnectionProfileRepository.save(profile);
        return new ResponseDto(SUCCESS, String.format("Connection profile saved with %d.", profile.getDatabaseConnectionProfileId()),
            this.toDto(profile));
    }

    @Override
    @Transactional
    public ResponseDto updateConnectionProfile(DatabaseConnectionProfileDto dto) throws Exception {
        if (isNull(dto.getDatabaseConnectionProfileId())) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        ResponseDto validationError = this.validate(dto, false);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(dto.getDatabaseConnectionProfileId());
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", dto.getDatabaseConnectionProfileId()));
        }
        DatabaseConnectionProfile profile = profileOpt.get();
        this.applyDto(profile, dto);
        if (!isNull(dto.getStatus())) {
            profile.setStatus(dto.getStatus());
        }
        profile.setUpdatedBy(TenantContext.getAppUserId());
        profile.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        this.databaseConnectionProfileRepository.save(profile);
        return new ResponseDto(SUCCESS, String.format("Connection profile saved with %d.", profile.getDatabaseConnectionProfileId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteConnectionProfile(Long databaseConnectionProfileId) throws Exception {
        if (isNull(databaseConnectionProfileId)) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(databaseConnectionProfileId);
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", databaseConnectionProfileId));
        }

        long queriesStillUsingIt = this.queryDefinitionRepository
            .countByDatabaseConnectionProfileIdAndStatusNot(databaseConnectionProfileId, Status.Delete);
        if (queriesStillUsingIt > 0) {
            return new ResponseDto(ERROR, String.format(
                "This connection profile is still used by %d saved quer%s -- update or delete %s first.",
                queriesStillUsingIt, queriesStillUsingIt == 1 ? "y" : "ies", queriesStillUsingIt == 1 ? "it" : "them"));
        }
        DatabaseConnectionProfile profile = profileOpt.get();
        profile.setStatus(Status.Delete);
        this.databaseConnectionProfileRepository.save(profile);
        return new ResponseDto(SUCCESS, String.format("Connection profile deleted with %d.", databaseConnectionProfileId));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllConnectionProfiles() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<DatabaseConnectionProfile> profiles = this.databaseConnectionProfileRepository
            .findByStatusNotOrderByDatabaseConnectionProfileIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data found.",
            profiles.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public ResponseDto fetchConnectionProfileById(Long databaseConnectionProfileId) throws Exception {
        if (isNull(databaseConnectionProfileId)) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(databaseConnectionProfileId);
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", databaseConnectionProfileId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.toDto(profileOpt.get()));
    }

    @Override
    @Transactional
    public ResponseDto testConnection(DatabaseConnectionProfileDto dto) throws Exception {
        DatabaseConnectionProfile profile;

        if (!isNull(dto.getDatabaseConnectionProfileId())) {
            this.tenantFilterHelper.enableIfNeeded(this.entityManager);
            Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(dto.getDatabaseConnectionProfileId());
            if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
                return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", dto.getDatabaseConnectionProfileId()));
            }
            profile = profileOpt.get();

            if (!isNull(dto.getPassword()) && !dto.getPassword().trim().isEmpty()) {
                profile.setPasswordEncrypted(this.encryptionUtil.encrypt(dto.getPassword()));
            }
            if (!isNull(dto.getHost())) {
                profile.setHost(dto.getHost());
            }
        } else {
            ResponseDto validationError = this.validate(dto, true);
            if (validationError != null) {
                return validationError;
            }
            profile = new DatabaseConnectionProfile();
            this.applyDto(profile, dto);
        }
        long startMs = System.currentTimeMillis();
        try (Connection connection = this.databaseConnectionFactory.openConnection(profile)) {
            if (!connection.isValid(5)) {
                return new ResponseDto(ERROR, "Connected, but the database did not respond to a validity check in time.");
            }
        } catch (Exception ex) {

            logger.warn("Connection test failed for profile '{}' (tenant {}): {}",
                dto.getProfileName(), TenantContext.getTenantId(), ex.getMessage());
            return new ResponseDto(ERROR, "Could not connect: " + this.sanitizeConnectionError(ex.getMessage()));
        }
        long elapsedMs = System.currentTimeMillis() - startMs;
        return new ResponseDto(SUCCESS, String.format("Connected successfully (%d ms).", elapsedMs));
    }

    private String sanitizeConnectionError(String rawMessage) {
        if (isNull(rawMessage)) {
            return "connection failed.";
        }

        String firstLine = rawMessage.split("\n", 2)[0];
        return firstLine.length() > 200 ? firstLine.substring(0, 200) + "..." : firstLine;
    }

    private ResponseDto validate(DatabaseConnectionProfileDto dto, boolean isNew) {
        if (isNull(dto.getProfileName()) || dto.getProfileName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "profileName missing.");
        }
        if (isNull(dto.getDatabaseType())) {
            return new ResponseDto(ERROR, "databaseType missing.");
        }
        if (isNull(dto.getHost()) || dto.getHost().trim().isEmpty()) {
            return new ResponseDto(ERROR, "host missing.");
        }
        if (isNull(dto.getPort()) || dto.getPort() < 1 || dto.getPort() > 65535) {
            return new ResponseDto(ERROR, "port must be between 1 and 65535.");
        }
        if (isNull(dto.getDatabaseName()) || dto.getDatabaseName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "databaseName missing.");
        }
        if (isNull(dto.getUsername()) || dto.getUsername().trim().isEmpty()) {
            return new ResponseDto(ERROR, "username missing.");
        }
        if (isNew && (isNull(dto.getPassword()) || dto.getPassword().trim().isEmpty())) {
            return new ResponseDto(ERROR, "password missing.");
        }
        return null;
    }

    private void applyDto(DatabaseConnectionProfile profile, DatabaseConnectionProfileDto dto) {
        profile.setProfileName(dto.getProfileName());
        profile.setDatabaseType(dto.getDatabaseType());
        profile.setHost(dto.getHost());
        profile.setPort(dto.getPort());
        profile.setDatabaseName(dto.getDatabaseName());
        profile.setUsername(dto.getUsername());
        if (!isNull(dto.getPassword()) && !dto.getPassword().trim().isEmpty()) {
            profile.setPasswordEncrypted(this.encryptionUtil.encrypt(dto.getPassword()));
        }
        profile.setAdditionalProperties(dto.getAdditionalProperties());
    }

    private DatabaseConnectionProfileDto toDto(DatabaseConnectionProfile profile) {
        DatabaseConnectionProfileDto dto = new DatabaseConnectionProfileDto();
        dto.setDatabaseConnectionProfileId(profile.getDatabaseConnectionProfileId());
        dto.setProfileName(profile.getProfileName());
        dto.setDatabaseType(profile.getDatabaseType());
        dto.setHost(profile.getHost());
        dto.setPort(profile.getPort());
        dto.setDatabaseName(profile.getDatabaseName());
        dto.setUsername(profile.getUsername());
        dto.setPasswordConfigured(!isNull(profile.getPasswordEncrypted()));
        dto.setAdditionalProperties(profile.getAdditionalProperties());
        dto.setStatus(profile.getStatus());
        dto.setCreatedAt(profile.getCreatedAt());
        dto.setUpdatedAt(profile.getUpdatedAt());
        return dto;
    }

}
