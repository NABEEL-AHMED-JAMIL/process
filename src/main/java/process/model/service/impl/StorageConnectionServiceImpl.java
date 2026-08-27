package process.model.service.impl;

import org.slf4j.Logger;
import process.util.UserNameResolver;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.config.StorageClientFactory;
import process.model.dto.ResponseDto;
import process.model.dto.StorageConnectionDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.model.service.StorageConnectionService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class StorageConnectionServiceImpl implements StorageConnectionService {

    private static final Logger logger = LoggerFactory.getLogger(StorageConnectionServiceImpl.class);

    private final StorageConnectionRepository storageConnectionRepository;
    private final StorageClientFactory storageClientFactory;
    private final EncryptionUtil encryptionUtil;
    private final TenantFilterHelper tenantFilterHelper;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;


    public StorageConnectionServiceImpl(StorageConnectionRepository storageConnectionRepository,
        StorageClientFactory storageClientFactory,
        EncryptionUtil encryptionUtil,
        TenantFilterHelper tenantFilterHelper,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.storageConnectionRepository = storageConnectionRepository;
        this.storageClientFactory = storageClientFactory;
        this.encryptionUtil = encryptionUtil;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    private boolean isOwnedByCaller(StorageConnection connection) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return connection != null && Objects.equals(connection.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addConnection(StorageConnectionDto dto) throws Exception {
        ResponseDto validationError = this.validate(dto, true);
        if (validationError != null) {
            return validationError;
        }
        String alias = dto.getAlias().trim();
        if (this.storageConnectionRepository.findByAlias(alias).isPresent()) {
            return new ResponseDto(ERROR, String.format(
                "A storage connection with the alias '%s' already exists -- aliases must be unique.", alias));
        }
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(TenantContext.getTenantId());
        this.applyDto(connection, dto);
        connection.setStatus(Status.Active);
        connection.setConnectionStatus("UNTESTED");
        connection.setDateCreated(new Timestamp(System.currentTimeMillis()));
        connection = this.storageConnectionRepository.save(connection);
        return new ResponseDto(SUCCESS,
            String.format("Storage connection saved with %s.", connection.getStorageConnectionId()),
            this.toDto(connection));
    }

    /**
     * Copies an existing connection, changing only what identifies it.
     *
     * Server-side because the secret never leaves the server: toDto withholds it deliberately,
     * so a copy assembled in the browser would arrive with no credential and the new connection
     * would fail its first test. Here the encrypted value moves across without being decrypted.
     *
     * Everything else -- provider, endpoint, region, host, port, TLS and passive flags -- comes
     * from the source, which is the point: the caller only says what the new one is called and
     * which bucket it points at.
     */
    @Override
    @Transactional
    public ResponseDto cloneConnection(Long sourceId, StorageConnectionDto dto) throws Exception {
        if (isNull(sourceId)) {
            return new ResponseDto(ERROR, "sourceId missing.");
        }
        Optional<StorageConnection> sourceOpt = this.storageConnectionRepository.findById(sourceId);
        if (!sourceOpt.isPresent() || !this.isOwnedByCaller(sourceOpt.get())
            || sourceOpt.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, String.format("Storage connection not found with %d.", sourceId));
        }
        if (isNull(dto) || isNull(dto.getAlias()) || dto.getAlias().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Alias missing.");
        }
        String alias = dto.getAlias().trim();
        if (this.storageConnectionRepository.findByAlias(alias).isPresent()) {
            return new ResponseDto(ERROR, String.format(
                "A storage connection with the alias '%s' already exists -- aliases must be unique.", alias));
        }
        StorageConnection source = sourceOpt.get();
        StorageConnection copy = new StorageConnection();
        // The tenant is the caller's own, never the source's: a platform admin cloning a
        // platform connection keeps it platform-level, and a tenant admin can only ever produce
        // one that belongs to their own workspace.
        copy.setTenantId(TenantContext.getTenantId());
        copy.setAlias(alias);
        copy.setConnectionName(isNull(dto.getConnectionName()) || dto.getConnectionName().trim().isEmpty()
            ? source.getConnectionName() + " (copy)" : dto.getConnectionName().trim());
        copy.setBucketName(isNull(dto.getBucketName()) || dto.getBucketName().trim().isEmpty()
            ? source.getBucketName() : dto.getBucketName().trim());
        copy.setDescription(isNull(dto.getDescription()) ? source.getDescription() : dto.getDescription());
        copy.setProvider(source.getProvider());
        copy.setEndpoint(source.getEndpoint());
        copy.setRegion(source.getRegion());
        copy.setBaseDirectory(source.getBaseDirectory());
        copy.setHost(source.getHost());
        copy.setPort(source.getPort());
        copy.setUsername(source.getUsername());
        copy.setImplicitTls(source.getImplicitTls());
        copy.setPassiveMode(source.getPassiveMode());
        copy.setAzureAccountName(source.getAzureAccountName());
        // Encrypted values carried across as-is -- never decrypted, never returned.
        copy.setAccessKey(source.getAccessKey());
        copy.setSecretKeyEnc(source.getSecretKeyEnc());
        copy.setPasswordEnc(source.getPasswordEnc());
        copy.setAzureConnectionStringEnc(source.getAzureConnectionStringEnc());
        copy.setIsDefault(false);
        copy.setStatus(Status.Active);
        // Untested on purpose: it points somewhere new, and inheriting the source's green tick
        // would claim a bucket had been reached that nobody has reached yet.
        copy.setConnectionStatus("UNTESTED");
        copy.setDateCreated(new Timestamp(System.currentTimeMillis()));
        copy = this.storageConnectionRepository.save(copy);
        return new ResponseDto(SUCCESS,
            String.format("Cloned to \"%s\". Test it before relying on it.", copy.getAlias()),
            this.toDto(copy));
    }

    @Override
    @Transactional
    public ResponseDto updateConnection(StorageConnectionDto dto) throws Exception {
        if (isNull(dto.getStorageConnectionId())) {
            return new ResponseDto(ERROR, "storageConnectionId missing.");
        }
        ResponseDto validationError = this.validate(dto, false);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<StorageConnection> existing = this.storageConnectionRepository.findById(dto.getStorageConnectionId());
        if (!existing.isPresent() || !this.isOwnedByCaller(existing.get())) {
            return new ResponseDto(ERROR,
                String.format("Storage connection not found with %d.", dto.getStorageConnectionId()));
        }
        String alias = dto.getAlias().trim();
        Optional<StorageConnection> aliasOwner = this.storageConnectionRepository.findByAlias(alias);
        if (aliasOwner.isPresent()
            && !Objects.equals(aliasOwner.get().getStorageConnectionId(), dto.getStorageConnectionId())) {
            return new ResponseDto(ERROR, String.format(
                "A different storage connection already uses the alias '%s'.", alias));
        }
        StorageConnection connection = existing.get();
        this.applyDto(connection, dto);
        if (!isNull(dto.getStatus())) {
            connection.setStatus(dto.getStatus());
        }
        // Settings changed, so any client cached from the previous values is now stale.
        this.storageClientFactory.evict(connection);
        this.storageConnectionRepository.save(connection);
        return new ResponseDto(SUCCESS,
            String.format("Storage connection saved with %s.", connection.getStorageConnectionId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteConnection(Long storageConnectionId) throws Exception {
        if (isNull(storageConnectionId)) {
            return new ResponseDto(ERROR, "storageConnectionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<StorageConnection> existing = this.storageConnectionRepository.findById(storageConnectionId);
        if (!existing.isPresent() || !this.isOwnedByCaller(existing.get())) {
            return new ResponseDto(ERROR, String.format("Storage connection not found with %d.", storageConnectionId));
        }
        StorageConnection connection = existing.get();
        connection.setStatus(Status.Delete);
        this.storageClientFactory.evict(connection);
        this.storageConnectionRepository.save(connection);
        return new ResponseDto(SUCCESS, String.format("Storage connection deleted with %s.", storageConnectionId));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllConnections() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<StorageConnection> connections =
            this.storageConnectionRepository.findByStatusNotOrderByStorageConnectionIdDesc(Status.Delete);
        List<StorageConnectionDto> dtos = connections.stream().map(this::toDto).collect(Collectors.toList());
        this.userNameResolver.attachToDtos(dtos, this.storageConnectionRepository,
            StorageConnection::getStorageConnectionId);
        return new ResponseDto(SUCCESS, "Data found.", dtos);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchConnectionById(Long storageConnectionId) throws Exception {
        if (isNull(storageConnectionId)) {
            return new ResponseDto(ERROR, "storageConnectionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<StorageConnection> connection = this.storageConnectionRepository.findById(storageConnectionId);
        if (!connection.isPresent() || !this.isOwnedByCaller(connection.get())) {
            return new ResponseDto(ERROR, String.format("Storage connection not found with %d.", storageConnectionId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.toDto(connection.get()));
    }

    @Override
    @Transactional
    public ResponseDto testConnection(Long storageConnectionId) throws Exception {
        if (isNull(storageConnectionId)) {
            return new ResponseDto(ERROR, "storageConnectionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<StorageConnection> existing = this.storageConnectionRepository.findById(storageConnectionId);
        if (!existing.isPresent() || !this.isOwnedByCaller(existing.get())) {
            return new ResponseDto(ERROR, String.format("Storage connection not found with %d.", storageConnectionId));
        }
        StorageConnection connection = existing.get();
        String message;
        boolean ok;
        try {
            ObjectStorageService service = this.storageClientFactory.buildUncached(connection);
            if (service instanceof FtpObjectStorageServiceImpl) {
                message = ((FtpObjectStorageServiceImpl) service).testConnection();
            } else {
                // For the object stores, listing the root of the target bucket/container is
                // the cheapest call that proves credentials, endpoint, and permissions all work.
                int count = service.listObjects(this.targetBucket(connection), "", null, 1).getObjects().size();
                message = "Connected -- " + this.targetBucket(connection) + " is readable ("
                    + (count > 0 ? "returned objects" : "empty or no objects at root") + ").";
            }
            ok = true;
        } catch (Exception e) {
            logger.warn("Storage connection test failed for {}: {}", connection.getAlias(), e.getMessage());
            message = this.rootCause(e);
            ok = false;
        }
        connection.setConnectionStatus(ok ? "SUCCESS" : "FAILED");
        connection.setLastTestedAt(new Timestamp(System.currentTimeMillis()));
        connection.setLastTestMessage(message);
        this.storageConnectionRepository.save(connection);
        return ok
            ? new ResponseDto(SUCCESS, message, this.toDto(connection))
            : new ResponseDto(ERROR, message, this.toDto(connection));
    }

    private String rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    private String targetBucket(StorageConnection connection) {
        return connection.getBucketName() != null && !connection.getBucketName().trim().isEmpty()
            ? connection.getBucketName().trim()
            : connection.getAlias();
    }

    private ResponseDto validate(StorageConnectionDto dto, boolean isCreate) {
        if (isNull(dto.getConnectionName()) || dto.getConnectionName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "connectionName missing.");
        }
        if (isNull(dto.getAlias()) || dto.getAlias().trim().isEmpty()) {
            return new ResponseDto(ERROR, "alias missing.");
        }
        if (!dto.getAlias().trim().matches("[A-Za-z0-9._-]+")) {
            return new ResponseDto(ERROR,
                "alias may only contain letters, numbers, dots, dashes and underscores.");
        }
        if (isNull(dto.getProvider())) {
            return new ResponseDto(ERROR, "provider missing (MINIO, S3, AZURE, FTP or FTPS).");
        }
        StorageProvider provider = dto.getProvider();
        if (provider.isFtpFamily()) {
            if (isNull(dto.getHost()) || dto.getHost().trim().isEmpty()) {
                return new ResponseDto(ERROR, "host is required for an FTP/FTPS connection.");
            }
            if (isNull(dto.getUsername()) || dto.getUsername().trim().isEmpty()) {
                return new ResponseDto(ERROR, "username is required for an FTP/FTPS connection.");
            }
            if (isCreate && (isNull(dto.getPassword()) || dto.getPassword().trim().isEmpty())) {
                return new ResponseDto(ERROR, "password is required for an FTP/FTPS connection.");
            }
            return null;
        }
        if (isNull(dto.getBucketName()) || dto.getBucketName().trim().isEmpty()) {
            return new ResponseDto(ERROR,
                provider == StorageProvider.AZURE
                    ? "container name (bucketName) is required for an Azure connection."
                    : "bucketName is required for this provider.");
        }
        if (provider == StorageProvider.MINIO && (isNull(dto.getEndpoint()) || dto.getEndpoint().trim().isEmpty())) {
            return new ResponseDto(ERROR, "endpoint is required for a MinIO connection.");
        }
        if (provider == StorageProvider.AZURE && isCreate
            && (isNull(dto.getAzureConnectionString()) || dto.getAzureConnectionString().trim().isEmpty())
            && (isNull(dto.getAzureAccountName()) || dto.getAzureAccountName().trim().isEmpty())) {
            return new ResponseDto(ERROR,
                "an Azure connection needs either a connection string, or an account name plus account key.");
        }
        return null;
    }

    @Override
    public ResponseDto discoverBuckets(StorageConnectionDto dto) throws Exception {
        if (isNull(dto) || isNull(dto.getProvider())) {
            return new ResponseDto(ERROR, "Select a provider first.");
        }
        // Discovery runs before a connection is saved, so the credentials arrive on the DTO.
        // When editing an existing one the secret is deliberately never sent back to the
        // browser, so an absent secret means "reuse the stored one" rather than "no secret".
        StorageConnection probe = new StorageConnection();
        if (!isNull(dto.getStorageConnectionId())) {
            this.tenantFilterHelper.enableIfNeeded(this.entityManager);
            Optional<StorageConnection> existing =
                this.storageConnectionRepository.findById(dto.getStorageConnectionId());
            if (!existing.isPresent() || !this.isOwnedByCaller(existing.get())) {
                return new ResponseDto(ERROR,
                    String.format("Storage connection not found with %d.", dto.getStorageConnectionId()));
            }
            StorageConnection saved = existing.get();
            probe.setSecretKeyEnc(saved.getSecretKeyEnc());
            probe.setAzureConnectionStringEnc(saved.getAzureConnectionStringEnc());
            probe.setPasswordEnc(saved.getPasswordEnc());
        }
        // Only the fields a connection attempt actually needs -- deliberately not applyDto(),
        // which is the save-path mapper and demands a name and alias. Discovery runs while the
        // form is still half-filled, so requiring those would defeat the point.
        probe.setProvider(dto.getProvider());
        probe.setEndpoint(this.trimToNull(dto.getEndpoint()));
        probe.setRegion(this.trimToNull(dto.getRegion()));
        probe.setAccessKey(this.trimToNull(dto.getAccessKey()));
        if (!isNull(dto.getSecretKey()) && !dto.getSecretKey().trim().isEmpty()) {
            probe.setSecretKeyEnc(this.encryptionUtil.encrypt(dto.getSecretKey().trim()));
        }
        if (!isNull(dto.getAzureConnectionString()) && !dto.getAzureConnectionString().trim().isEmpty()) {
            probe.setAzureConnectionStringEnc(this.encryptionUtil.encrypt(dto.getAzureConnectionString().trim()));
        }
        try {
            List<String> buckets = this.storageClientFactory.listAvailableBuckets(probe);
            return new ResponseDto(SUCCESS,
                buckets.isEmpty()
                    ? "These credentials work, but no buckets were returned."
                    : String.format("Found %d bucket%s.", buckets.size(), buckets.size() == 1 ? "" : "s"),
                buckets);
        } catch (Exception e) {
            String cause = this.rootCause(e);
            logger.warn("Bucket discovery failed for provider {}: {}", dto.getProvider(), cause);
            // A key scoped to one bucket can read it fine yet be unable to enumerate, so say
            // that plainly instead of implying the credentials themselves are wrong.
            if (cause != null && (cause.contains("AccessDenied") || cause.contains("not authorized")
                || cause.contains("403"))) {
                return new ResponseDto(ERROR, "These credentials can't list buckets "
                    + "(s3:ListAllMyBuckets is missing). Type the bucket name instead -- reading "
                    + "a specific bucket may still work.");
            }
            return new ResponseDto(ERROR, cause);
        }
    }

    private void applyDto(StorageConnection connection, StorageConnectionDto dto) {
        connection.setConnectionName(dto.getConnectionName().trim());
        connection.setAlias(dto.getAlias().trim());
        connection.setProvider(dto.getProvider());
        connection.setDescription(dto.getDescription());
        connection.setBucketName(this.trimToNull(dto.getBucketName()));
        connection.setEndpoint(this.trimToNull(dto.getEndpoint()));
        connection.setRegion(this.trimToNull(dto.getRegion()));
        connection.setAccessKey(this.trimToNull(dto.getAccessKey()));
        connection.setAzureAccountName(this.trimToNull(dto.getAzureAccountName()));
        connection.setHost(this.trimToNull(dto.getHost()));
        connection.setPort(dto.getPort());
        connection.setUsername(this.trimToNull(dto.getUsername()));
        connection.setBaseDirectory(this.trimToNull(dto.getBaseDirectory()));
        if (dto.getPassiveMode() != null) {
            connection.setPassiveMode(dto.getPassiveMode());
        }
        if (dto.getImplicitTls() != null) {
            connection.setImplicitTls(dto.getImplicitTls());
        }
        if (dto.getIsDefault() != null) {
            connection.setIsDefault(dto.getIsDefault());
        }
        // Secrets: a blank value on update means "leave the stored one alone", so that editing
        // an unrelated field doesn't require re-typing every credential (and so the UI can show
        // an empty password box without silently wiping what's saved).
        if (!isNull(dto.getSecretKey()) && !dto.getSecretKey().trim().isEmpty()) {
            connection.setSecretKeyEnc(this.encryptionUtil.encrypt(dto.getSecretKey().trim()));
        }
        if (!isNull(dto.getAzureConnectionString()) && !dto.getAzureConnectionString().trim().isEmpty()) {
            connection.setAzureConnectionStringEnc(
                this.encryptionUtil.encrypt(dto.getAzureConnectionString().trim()));
        }
        if (!isNull(dto.getPassword()) && !dto.getPassword().trim().isEmpty()) {
            connection.setPasswordEnc(this.encryptionUtil.encrypt(dto.getPassword().trim()));
        }
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private StorageConnectionDto toDto(StorageConnection connection) {
        StorageConnectionDto dto = new StorageConnectionDto();
        dto.setStorageConnectionId(connection.getStorageConnectionId());
        dto.setTenantId(connection.getTenantId());
        dto.setConnectionName(connection.getConnectionName());
        dto.setAlias(connection.getAlias());
        dto.setProvider(connection.getProvider());
        dto.setDescription(connection.getDescription());
        dto.setBucketName(connection.getBucketName());
        dto.setEndpoint(connection.getEndpoint());
        dto.setRegion(connection.getRegion());
        dto.setAccessKey(connection.getAccessKey());
        dto.setAzureAccountName(connection.getAzureAccountName());
        dto.setHost(connection.getHost());
        dto.setPort(connection.getPort());
        dto.setUsername(connection.getUsername());
        dto.setBaseDirectory(connection.getBaseDirectory());
        dto.setPassiveMode(connection.getPassiveMode());
        dto.setImplicitTls(connection.getImplicitTls());
        dto.setIsDefault(connection.getIsDefault());
        dto.setStatus(connection.getStatus());
        dto.setConnectionStatus(connection.getConnectionStatus());
        dto.setLastTestedAt(connection.getLastTestedAt());
        dto.setLastTestMessage(connection.getLastTestMessage());
        dto.setDateCreated(connection.getDateCreated());
        // Only whether a secret exists, never the secret itself.
        dto.setSecretKeyConfigured(!isNull(connection.getSecretKeyEnc()));
        dto.setAzureConnectionStringConfigured(!isNull(connection.getAzureConnectionStringEnc()));
        dto.setPasswordConfigured(!isNull(connection.getPasswordEnc()));
        return dto;
    }

}
