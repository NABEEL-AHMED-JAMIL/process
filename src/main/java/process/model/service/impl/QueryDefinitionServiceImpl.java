package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.engine.query.DatabaseConnectionFactory;
import process.engine.query.QueryValidator;
import process.model.dto.QueryDefinitionDto;
import process.model.dto.QueryPreviewResponseDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.service.QueryDefinitionService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
public class QueryDefinitionServiceImpl implements QueryDefinitionService {

    private static final Logger logger = LoggerFactory.getLogger(QueryDefinitionServiceImpl.class);

    private static final int PREVIEW_ROW_LIMIT = 100;
    private static final int PREVIEW_TIMEOUT_SECONDS = 15;

    private final QueryDefinitionRepository queryDefinitionRepository;
    private final DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    private final EncryptionUtil encryptionUtil;
    private final TenantFilterHelper tenantFilterHelper;
    private final QueryValidator queryValidator;
    private final DatabaseConnectionFactory databaseConnectionFactory;
    private final UserNameResolver userNameResolver;

    @PersistenceContext
    private EntityManager entityManager;

    public QueryDefinitionServiceImpl(QueryDefinitionRepository queryDefinitionRepository,
        DatabaseConnectionProfileRepository databaseConnectionProfileRepository, EncryptionUtil encryptionUtil,
        TenantFilterHelper tenantFilterHelper, QueryValidator queryValidator,
        DatabaseConnectionFactory databaseConnectionFactory, UserNameResolver userNameResolver) {
        this.queryDefinitionRepository = queryDefinitionRepository;
        this.databaseConnectionProfileRepository = databaseConnectionProfileRepository;
        this.encryptionUtil = encryptionUtil;
        this.tenantFilterHelper = tenantFilterHelper;
        this.queryValidator = queryValidator;
        this.databaseConnectionFactory = databaseConnectionFactory;
        this.userNameResolver = userNameResolver;
    }

    private boolean isOwnedByCaller(QueryDefinition query) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return query != null && Objects.equals(query.getTenantId(), TenantContext.getTenantId());
    }

    private boolean isOwnedByCaller(DatabaseConnectionProfile profile) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return profile != null && Objects.equals(profile.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addQuery(QueryDefinitionDto dto) throws Exception {
        ResponseDto validationError = this.validateBasic(dto);
        if (validationError != null) {
            return validationError;
        }

        if (isNull(TenantContext.getTenantId())) {
            return new ResponseDto(ERROR, "A platform admin can't own a query directly -- log in as a tenant user to create one.");
        }
        QueryValidator.ValidationResult sqlCheck = this.queryValidator.validate(dto.getQueryText());
        if (!sqlCheck.isValid()) {
            return new ResponseDto(ERROR, sqlCheck.getReason());
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        ResponseDto ownershipError = this.checkConnectionProfileOwnership(dto.getDatabaseConnectionProfileId());
        if (ownershipError != null) {
            return ownershipError;
        }
        QueryDefinition query = new QueryDefinition();
        query.setTenantId(TenantContext.getTenantId());
        query.setQueryName(dto.getQueryName());
        query.setQueryText(this.encryptionUtil.encrypt(sqlCheck.getNormalizedSql()));
        query.setDatabaseConnectionProfileId(dto.getDatabaseConnectionProfileId());
        query.setStatus(Status.Active);
        query.setCreatedBy(TenantContext.getAppUserId());
        query.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        query = this.queryDefinitionRepository.save(query);
        return new ResponseDto(SUCCESS, String.format("Query saved with %d.", query.getQueryId()), this.toSummaryDto(query));
    }

    @Override
    @Transactional
    public ResponseDto updateQuery(QueryDefinitionDto dto) throws Exception {
        if (isNull(dto.getQueryId())) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        ResponseDto validationError = this.validateBasic(dto);
        if (validationError != null) {
            return validationError;
        }
        QueryValidator.ValidationResult sqlCheck = this.queryValidator.validate(dto.getQueryText());
        if (!sqlCheck.isValid()) {
            return new ResponseDto(ERROR, sqlCheck.getReason());
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(dto.getQueryId());
        if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", dto.getQueryId()));
        }
        ResponseDto ownershipError = this.checkConnectionProfileOwnership(dto.getDatabaseConnectionProfileId());
        if (ownershipError != null) {
            return ownershipError;
        }
        QueryDefinition query = queryOpt.get();

        if (!isNull(dto.getVersion()) && !Objects.equals(dto.getVersion(), query.getVersion())) {
            return new ResponseDto(ERROR, "This query was changed by someone else since you opened it -- reload and try again.");
        }
        query.setQueryName(dto.getQueryName());
        query.setQueryText(this.encryptionUtil.encrypt(sqlCheck.getNormalizedSql()));
        query.setDatabaseConnectionProfileId(dto.getDatabaseConnectionProfileId());
        if (!isNull(dto.getStatus())) {
            query.setStatus(dto.getStatus());
        }
        query.setUpdatedBy(TenantContext.getAppUserId());
        query.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        this.queryDefinitionRepository.save(query);
        return new ResponseDto(SUCCESS, String.format("Query saved with %d.", query.getQueryId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteQuery(Long queryId) throws Exception {
        if (isNull(queryId)) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(queryId);
        if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", queryId));
        }
        QueryDefinition query = queryOpt.get();
        query.setStatus(Status.Delete);
        this.queryDefinitionRepository.save(query);
        return new ResponseDto(SUCCESS, String.format("Query deleted with %d.", queryId));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllQueries() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<QueryDefinition> queries = this.queryDefinitionRepository.findByStatusNotOrderByQueryIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data found.",
            queries.stream().map(this::toSummaryDto).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public ResponseDto fetchQueryById(Long queryId) throws Exception {
        if (isNull(queryId)) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(queryId);
        if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", queryId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.toDetailDto(queryOpt.get()));
    }

    @Override
    @Transactional
    public ResponseDto validateQuery(QueryDefinitionDto dto) throws Exception {
        String queryText = this.resolveQueryText(dto);
        if (queryText == null) {
            return new ResponseDto(ERROR, "queryId or queryText is required.");
        }
        QueryValidator.ValidationResult result = this.queryValidator.validate(queryText);
        return result.isValid()
            ? new ResponseDto(SUCCESS, "Query is valid.")
            : new ResponseDto(ERROR, result.getReason());
    }

    @Override
    @Transactional
    public ResponseDto previewQuery(QueryDefinitionDto dto) throws Exception {
        String queryText = this.resolveQueryText(dto);
        if (queryText == null) {
            return new ResponseDto(ERROR, "queryId or queryText is required.");
        }
        Long databaseConnectionProfileId = !isNull(dto.getQueryId())
            ? null
            : dto.getDatabaseConnectionProfileId();
        if (!isNull(dto.getQueryId())) {
            this.tenantFilterHelper.enableIfNeeded(this.entityManager);
            Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(dto.getQueryId());
            if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
                return new ResponseDto(ERROR, String.format("Query not found with %d.", dto.getQueryId()));
            }
            databaseConnectionProfileId = queryOpt.get().getDatabaseConnectionProfileId();
        }
        if (isNull(databaseConnectionProfileId)) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId is required.");
        }
        QueryValidator.ValidationResult sqlCheck = this.queryValidator.validate(queryText);
        if (!sqlCheck.isValid()) {
            return new ResponseDto(ERROR, sqlCheck.getReason());
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(databaseConnectionProfileId);
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", databaseConnectionProfileId));
        }

        String wrappedSql = "SELECT * FROM (" + sqlCheck.getNormalizedSql() + ") AS query_preview_wrapper LIMIT "
            + (PREVIEW_ROW_LIMIT + 1);
        try (Connection connection = this.databaseConnectionFactory.openConnection(profileOpt.get())) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(PREVIEW_TIMEOUT_SECONDS);
                try (ResultSet resultSet = statement.executeQuery(wrappedSql)) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int columnCount = metaData.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        columns.add(metaData.getColumnLabel(i));
                    }
                    List<Map<String, Object>> rows = new ArrayList<>();

                    boolean truncated = false;
                    while (resultSet.next()) {
                        if (rows.size() >= PREVIEW_ROW_LIMIT) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(columns.get(i - 1), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    return new ResponseDto(SUCCESS, "Preview ready.", new QueryPreviewResponseDto(columns, rows, truncated));
                }
            }
        } catch (Exception ex) {
            logger.warn("Preview failed for connection profile {} (tenant {}): {}",
                databaseConnectionProfileId, TenantContext.getTenantId(), ex.getMessage());
            return new ResponseDto(ERROR, "Preview failed: " + this.sanitizeDbError(ex.getMessage()));
        }
    }

    private String resolveQueryText(QueryDefinitionDto dto) {
        if (!isNull(dto.getQueryId())) {
            this.tenantFilterHelper.enableIfNeeded(this.entityManager);
            Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(dto.getQueryId());
            if (queryOpt.isPresent() && this.isOwnedByCaller(queryOpt.get())) {
                return this.encryptionUtil.decrypt(queryOpt.get().getQueryText());
            }
            return null;
        }
        return (!isNull(dto.getQueryText()) && !dto.getQueryText().trim().isEmpty()) ? dto.getQueryText() : null;
    }

    private ResponseDto checkConnectionProfileOwnership(Long databaseConnectionProfileId) {
        if (isNull(databaseConnectionProfileId)) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(databaseConnectionProfileId);
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", databaseConnectionProfileId));
        }
        return null;
    }

    private ResponseDto validateBasic(QueryDefinitionDto dto) {
        if (isNull(dto.getQueryName()) || dto.getQueryName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "queryName missing.");
        }
        if (isNull(dto.getQueryText()) || dto.getQueryText().trim().isEmpty()) {
            return new ResponseDto(ERROR, "queryText missing.");
        }
        if (isNull(dto.getDatabaseConnectionProfileId())) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        return null;
    }

    private String sanitizeDbError(String rawMessage) {
        if (isNull(rawMessage)) {
            return "the database returned an error.";
        }
        String firstLine = rawMessage.split("\n", 2)[0];
        return firstLine.length() > 300 ? firstLine.substring(0, 300) + "..." : firstLine;
    }

    private QueryDefinitionDto toSummaryDto(QueryDefinition query) {
        QueryDefinitionDto dto = new QueryDefinitionDto();
        dto.setQueryId(query.getQueryId());
        dto.setQueryName(query.getQueryName());
        dto.setDatabaseConnectionProfileId(query.getDatabaseConnectionProfileId());
        dto.setStatus(query.getStatus());
        dto.setVersion(query.getVersion());
        dto.setCreatedAt(query.getCreatedAt());
        dto.setUpdatedAt(query.getUpdatedAt());
        // The ids have been stored all along; without a name beside them every screen showed
        // work with no author.
        dto.setCreatedBy(query.getCreatedBy());
        dto.setCreatedByName(this.userNameResolver.nameFor(query.getCreatedBy()));
        dto.setUpdatedBy(query.getUpdatedBy());
        dto.setUpdatedByName(this.userNameResolver.nameFor(query.getUpdatedBy()));
        return dto;
    }

    private QueryDefinitionDto toDetailDto(QueryDefinition query) {
        QueryDefinitionDto dto = this.toSummaryDto(query);
        dto.setQueryText(this.encryptionUtil.decrypt(query.getQueryText()));
        return dto;
    }

}
