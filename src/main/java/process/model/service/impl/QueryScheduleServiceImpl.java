package process.model.service.impl;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.QueryScheduleDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.DatabaseConnectionProfile;
import process.model.pojo.QueryDefinition;
import process.model.pojo.QuerySchedule;
import process.model.repository.DatabaseConnectionProfileRepository;
import process.model.repository.QueryDefinitionRepository;
import process.model.repository.QueryScheduleRepository;
import process.model.service.QueryScheduleService;
import process.util.UserNameResolver;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

@Service
public class QueryScheduleServiceImpl implements QueryScheduleService {

    private final QueryScheduleRepository queryScheduleRepository;
    private final QueryDefinitionRepository queryDefinitionRepository;
    private final DatabaseConnectionProfileRepository databaseConnectionProfileRepository;
    private final TenantFilterHelper tenantFilterHelper;

    private final UserNameResolver userNameResolver;
    @PersistenceContext
    private EntityManager entityManager;

    public QueryScheduleServiceImpl(QueryScheduleRepository queryScheduleRepository,
        QueryDefinitionRepository queryDefinitionRepository,
        DatabaseConnectionProfileRepository databaseConnectionProfileRepository,
        TenantFilterHelper tenantFilterHelper,
        UserNameResolver userNameResolver) {
        this.queryScheduleRepository = queryScheduleRepository;
        this.queryDefinitionRepository = queryDefinitionRepository;
        this.databaseConnectionProfileRepository = databaseConnectionProfileRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.userNameResolver = userNameResolver;
    }

    private boolean isOwnedByCaller(QuerySchedule schedule) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return schedule != null && Objects.equals(schedule.getTenantId(), TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addSchedule(QueryScheduleDto dto) throws Exception {
        ResponseDto validationError = this.validate(dto);
        if (validationError != null) {
            return validationError;
        }

        if (isNull(TenantContext.getTenantId())) {
            return new ResponseDto(ERROR, "A platform admin can't own a schedule directly -- log in as a tenant user to create one.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        ResponseDto ownershipError = this.checkOwnership(dto);
        if (ownershipError != null) {
            return ownershipError;
        }
        QuerySchedule schedule = new QuerySchedule();
        schedule.setTenantId(TenantContext.getTenantId());
        this.applyDto(schedule, dto);
        schedule.setNextRunAt(this.computeNextRun(dto.getIntervalMinutes()));
        schedule.setStatus(Status.Active);
        schedule.setCreatedBy(TenantContext.getAppUserId());
        schedule.setCreatedAt(new Timestamp(System.currentTimeMillis()));
        schedule = this.queryScheduleRepository.save(schedule);
        return new ResponseDto(SUCCESS, String.format("Schedule saved with %d.", schedule.getScheduleId()), this.toDto(schedule));
    }

    @Override
    @Transactional
    public ResponseDto updateSchedule(QueryScheduleDto dto) throws Exception {
        if (isNull(dto.getScheduleId())) {
            return new ResponseDto(ERROR, "scheduleId missing.");
        }
        ResponseDto validationError = this.validate(dto);
        if (validationError != null) {
            return validationError;
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QuerySchedule> scheduleOpt = this.queryScheduleRepository.findById(dto.getScheduleId());
        if (!scheduleOpt.isPresent() || !this.isOwnedByCaller(scheduleOpt.get())) {
            return new ResponseDto(ERROR, String.format("Schedule not found with %d.", dto.getScheduleId()));
        }
        ResponseDto ownershipError = this.checkOwnership(dto);
        if (ownershipError != null) {
            return ownershipError;
        }
        QuerySchedule schedule = scheduleOpt.get();
        boolean intervalChanged = !Objects.equals(schedule.getIntervalMinutes(), dto.getIntervalMinutes());
        this.applyDto(schedule, dto);
        if (intervalChanged) {
            schedule.setNextRunAt(this.computeNextRun(dto.getIntervalMinutes()));
        }
        if (!isNull(dto.getStatus())) {
            schedule.setStatus(dto.getStatus());
        }
        schedule.setUpdatedBy(TenantContext.getAppUserId());
        schedule.setUpdatedAt(new Timestamp(System.currentTimeMillis()));
        this.queryScheduleRepository.save(schedule);
        return new ResponseDto(SUCCESS, String.format("Schedule saved with %d.", schedule.getScheduleId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteSchedule(Long scheduleId) throws Exception {
        if (isNull(scheduleId)) {
            return new ResponseDto(ERROR, "scheduleId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QuerySchedule> scheduleOpt = this.queryScheduleRepository.findById(scheduleId);
        if (!scheduleOpt.isPresent() || !this.isOwnedByCaller(scheduleOpt.get())) {
            return new ResponseDto(ERROR, String.format("Schedule not found with %d.", scheduleId));
        }
        QuerySchedule schedule = scheduleOpt.get();
        schedule.setStatus(Status.Delete);
        this.queryScheduleRepository.save(schedule);
        return new ResponseDto(SUCCESS, String.format("Schedule deleted with %d.", scheduleId));
    }

    @Override
    @Transactional
    public ResponseDto fetchAllSchedules() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<QuerySchedule> schedules = this.queryScheduleRepository.findByStatusNotOrderByScheduleIdDesc(Status.Delete);
        return new ResponseDto(SUCCESS, "Data found.", schedules.stream().map(this::toDto).collect(Collectors.toList()));
    }

    @Override
    @Transactional
    public ResponseDto fetchScheduleById(Long scheduleId) throws Exception {
        if (isNull(scheduleId)) {
            return new ResponseDto(ERROR, "scheduleId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<QuerySchedule> scheduleOpt = this.queryScheduleRepository.findById(scheduleId);
        if (!scheduleOpt.isPresent() || !this.isOwnedByCaller(scheduleOpt.get())) {
            return new ResponseDto(ERROR, String.format("Schedule not found with %d.", scheduleId));
        }
        return new ResponseDto(SUCCESS, "Data found.", this.toDto(scheduleOpt.get()));
    }

    @Override
    public List<QuerySchedule> findDueSchedules(Timestamp now) {

        return this.queryScheduleRepository.findDueSchedules(now);
    }

    @Override
    @Transactional
    public void advanceNextRun(Long scheduleId) throws Exception {
        Optional<QuerySchedule> scheduleOpt = this.queryScheduleRepository.findById(scheduleId);
        if (!scheduleOpt.isPresent()) {
            return;
        }
        QuerySchedule schedule = scheduleOpt.get();
        schedule.setNextRunAt(this.computeNextRun(schedule.getIntervalMinutes()));
        this.queryScheduleRepository.save(schedule);
    }

    private Timestamp computeNextRun(Integer intervalMinutes) {
        return new Timestamp(System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(intervalMinutes));
    }

    private ResponseDto checkOwnership(QueryScheduleDto dto) {
        Optional<QueryDefinition> queryOpt = this.queryDefinitionRepository.findById(dto.getQueryId());
        if (!queryOpt.isPresent() || (!TenantContext.isPlatformAdmin()
            && !Objects.equals(queryOpt.get().getTenantId(), TenantContext.getTenantId()))) {
            return new ResponseDto(ERROR, String.format("Query not found with %d.", dto.getQueryId()));
        }
        Optional<DatabaseConnectionProfile> profileOpt = this.databaseConnectionProfileRepository.findById(dto.getDatabaseConnectionProfileId());
        if (!profileOpt.isPresent() || (!TenantContext.isPlatformAdmin()
            && !Objects.equals(profileOpt.get().getTenantId(), TenantContext.getTenantId()))) {
            return new ResponseDto(ERROR, String.format("Connection profile not found with %d.", dto.getDatabaseConnectionProfileId()));
        }
        return null;
    }

    private ResponseDto validate(QueryScheduleDto dto) {
        if (isNull(dto.getQueryId())) {
            return new ResponseDto(ERROR, "queryId missing.");
        }
        if (isNull(dto.getDatabaseConnectionProfileId())) {
            return new ResponseDto(ERROR, "databaseConnectionProfileId missing.");
        }
        if (isNull(dto.getOutputBucket()) || dto.getOutputBucket().trim().isEmpty()) {
            return new ResponseDto(ERROR, "outputBucket missing.");
        }
        if (isNull(dto.getOutputFileNameTemplate()) || dto.getOutputFileNameTemplate().trim().isEmpty()) {
            return new ResponseDto(ERROR, "outputFileNameTemplate missing.");
        }
        if (isNull(dto.getIntervalMinutes()) || dto.getIntervalMinutes() < 5) {
            return new ResponseDto(ERROR, "intervalMinutes must be at least 5.");
        }
        return null;
    }

    private void applyDto(QuerySchedule schedule, QueryScheduleDto dto) {
        schedule.setQueryId(dto.getQueryId());
        schedule.setDatabaseConnectionProfileId(dto.getDatabaseConnectionProfileId());
        schedule.setOutputBucket(dto.getOutputBucket());
        schedule.setOutputPrefix(isNull(dto.getOutputPrefix()) ? "" : dto.getOutputPrefix());
        schedule.setOutputFileNameTemplate(dto.getOutputFileNameTemplate());
        schedule.setIntervalMinutes(dto.getIntervalMinutes());
    }

    private QueryScheduleDto toDto(QuerySchedule schedule) {
        QueryScheduleDto dto = new QueryScheduleDto();
        dto.setScheduleId(schedule.getScheduleId());
        dto.setQueryId(schedule.getQueryId());
        dto.setDatabaseConnectionProfileId(schedule.getDatabaseConnectionProfileId());
        dto.setOutputBucket(schedule.getOutputBucket());
        dto.setOutputPrefix(schedule.getOutputPrefix());
        dto.setOutputFileNameTemplate(schedule.getOutputFileNameTemplate());
        dto.setIntervalMinutes(schedule.getIntervalMinutes());
        dto.setNextRunAt(schedule.getNextRunAt());
        dto.setStatus(schedule.getStatus());
        dto.setCreatedBy(schedule.getCreatedBy());
        dto.setCreatedByName(this.userNameResolver.nameFor(schedule.getCreatedBy()));
        dto.setUpdatedBy(schedule.getUpdatedBy());
        dto.setUpdatedByName(this.userNameResolver.nameFor(schedule.getUpdatedBy()));
        this.queryDefinitionRepository.findById(schedule.getQueryId()).ifPresent(q -> dto.setQueryName(q.getQueryName()));
        return dto;
    }

}
