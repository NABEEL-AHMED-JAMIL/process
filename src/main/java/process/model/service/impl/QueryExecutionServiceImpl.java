package process.model.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.QueryExecutionDto;
import process.model.dto.QueryExecutionRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.QueryExecutionStatus;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.pojo.QueryExecution;
import process.model.pojo.QuerySchedule;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.repository.QueryExecutionRepository;
import process.model.service.QueryExecutionService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
public class QueryExecutionServiceImpl implements QueryExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(QueryExecutionServiceImpl.class);

    private final QueryExecutionRepository queryExecutionRepository;
    private final QueryDefinitionRepository queryDefinitionRepository;
    private final DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    private final TenantFilterHelper tenantFilterHelper;
    private final QueryExecutionRunner queryExecutionRunner;

    @PersistenceContext
    private EntityManager entityManager;

    public QueryExecutionServiceImpl(QueryExecutionRepository queryExecutionRepository,
        QueryDefinitionRepository queryDefinitionRepository,
        DatabaseConnectionProfileRepository databaseConnectionProfileRepository,
        TenantFilterHelper tenantFilterHelper, QueryExecutionRunner queryExecutionRunner) {
        this.queryExecutionRepository = queryExecutionRepository;
        this.queryDefinitionRepository = queryDefinitionRepository;
        this.databaseConnectionProfileRepository = databaseConnectionProfileRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.queryExecutionRunner = queryExecutionRunner;
    }

    @Override
    @Transactional
    public ResponseDto execute(QueryExecutionRequestDto request) throws Exception {
        if (isNull(request.getQueryId())) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        if (isNull(request.getOutputBucket()) || request.getOutputBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "outputBucket missing.");
        }
        if (isNull(request.getOutputFileName()) || request.getOutputFileName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "outputFileName missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(request.getQueryId());
        if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", request.getQueryId()));
        }
        QueryDefinition query = queryOpt.get();

        Long connectionProfileId = !isNull(request.getDatabaseConnectionProfileId())
            ? request.getDatabaseConnectionProfileId() : query.getDatabaseConnectionProfileId();
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(connectionProfileId);
        if (!profileOpt.isPresent() || !this.isOwnedByCaller(profileOpt.get())) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", connectionProfileId));
        }
        String outputPrefix = isNull(request.getOutputPrefix()) ? "" : request.getOutputPrefix();
        QueryExecution execution = this.queryExecutionRunner.runAndRecord(query, profileOpt.get(),
            request.getOutputBucket(), outputPrefix, request.getOutputFileName(), null);
        return execution.getStatus() == QueryExecutionStatus.SUCCESS
            ? new ResponseDto(SUCCESS, "Query executed successfully.", this.toDto(execution))
            : new ResponseDto(ERROR, execution.getErrorMessage(), this.toDto(execution));
    }

    @Override
    @Transactional
    public void executeForSchedule(QuerySchedule schedule) throws Exception {

        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(schedule.getQueryId());
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(schedule.getDatabaseConnectionProfileId());
        if (!queryOpt.isPresent() || !profileOpt.isPresent()) {
            logger.error("QuerySchedule {} (tenant {}) references a query/connection profile that no longer exists -- skipping this run.",
                schedule.getScheduleId(), schedule.getTenantId());
            return;
        }
        this.queryExecutionRunner.runAndRecord(queryOpt.get(), profileOpt.get(), schedule.getOutputBucket(),
            schedule.getOutputPrefix(), schedule.getOutputFileNameTemplate(), schedule.getScheduleId());
    }

    @Override
    @Transactional
    public ResponseDto fetchExecutionById(Long executionId) throws Exception {
        if (isNull(executionId)) {
            return new ResponseDto(ERROR, "executionId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryExecution> executionOpt = this.queryExecutionRepository.findById(executionId);
        if (!executionOpt.isPresent() || !this.isOwnedByCaller(executionOpt.get())) {
            return new ResponseDto(ERROR, String.format("Execution not found with %d.", executionId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.toDto(executionOpt.get()));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllExecutions() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<QueryExecution> executions = this.queryExecutionRepository.findTop50ByOrderByExecutionIdDesc();
        return new ResponseDto(SUCCESS, "Data found.",
            executions.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public ResponseDto fetchExecutionsByQueryId(Long queryId) throws Exception {
        if (isNull(queryId)) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(queryId);
        if (!queryOpt.isPresent() || !this.isOwnedByCaller(queryOpt.get())) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", queryId));
        }
        List<QueryExecution> executions = this.queryExecutionRepository.findByQueryIdOrderByExecutionIdDesc(queryId);
        return new ResponseDto(SUCCESS, "Data found.",
            executions.stream().map(this::toDto).collect(Collectors.toList()));
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

    private boolean isOwnedByCaller(QueryExecution execution) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return execution != null && Objects.equals(execution.getTenantId(), TenantContext.getTenantId());
    }

    private QueryExecutionDto toDto(QueryExecution execution) {
        QueryExecutionDto dto = new QueryExecutionDto();
        dto.setExecutionId(execution.getExecutionId());
        dto.setQueryId(execution.getQueryId());
        dto.setScheduleId(execution.getScheduleId());
        dto.setStatus(execution.getStatus());
        dto.setStartedAt(execution.getStartedAt());
        dto.setCompletedAt(execution.getCompletedAt());
        dto.setRowCount(execution.getRowCount());
        dto.setOutputBucket(execution.getOutputBucket());
        dto.setOutputKey(execution.getOutputKey());
        dto.setErrorMessage(execution.getErrorMessage());
        this.queryDefinitionRepository.findById(execution.getQueryId())
            .ifPresent(q -> dto.setQueryName(q.getQueryName()));
        return dto;
    }

}
