package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.dto.TenantDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.model.service.TenantService;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

@Service
public class TenantServiceImpl implements TenantService {

    private final Logger logger = LoggerFactory.getLogger(TenantServiceImpl.class);

    private static final String BUCKET_LIST = "BUCKET_LIST";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final KafkaConnectionProfileRepository kafkaConnectionProfileRepository;
    private final LookupDataRepository lookupDataRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final SourceJobRepository sourceJobRepository;

    public TenantServiceImpl(TenantRepository tenantRepository, AppUserRepository appUserRepository,
        KafkaConnectionProfileRepository kafkaConnectionProfileRepository, LookupDataRepository lookupDataRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository, SourceTaskRepository sourceTaskRepository,
        SourceJobRepository sourceJobRepository) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.kafkaConnectionProfileRepository = kafkaConnectionProfileRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.sourceJobRepository = sourceJobRepository;
    }

    @Override
    public ResponseDto listTenants() throws Exception {
        List<Tenant> tenants = this.tenantRepository.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete);
        List<TenantDto> tenantDtos = tenants.stream().map(this::mapToDtoWithStats).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Tenants fetched successfully.", tenantDtos);
    }

    private TenantDto mapToDtoWithStats(Tenant tenant) {
        TenantDto dto = this.mapToDto(tenant);
        Long tenantId = tenant.getTenantId();
        dto.setUserCount(this.appUserRepository.countByTenantIdAndStatusNot(tenantId, Status.Delete));
        dto.setKafkaProfileCount(this.kafkaConnectionProfileRepository.countByTenantIdAndStatusNot(tenantId, Status.Delete));
        dto.setBucketCount(this.lookupDataRepository.countByTenantIdAndParent_LookupType(tenantId, BUCKET_LIST));
        dto.setSourceTaskTypeCount(this.sourceTaskTypeRepository.countByTenantIdAndStatusNot(tenantId, Status.Delete));
        dto.setSourceTaskCount(this.sourceTaskRepository.countByTenantIdAndTaskStatusNot(tenantId, Status.Delete));
        dto.setSourceJobCount(this.sourceJobRepository.countByTenantIdAndJobStatusNot(tenantId, Status.Delete));
        return dto;
    }

    @Override
    @Transactional
    public ResponseDto addTenant(TenantDto tenantDto) throws Exception {
        if (isNull(tenantDto.getTenantName()) || tenantDto.getTenantName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Tenant name missing.");
        } else if (isNull(tenantDto.getTenantCode()) || tenantDto.getTenantCode().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Tenant code missing.");
        }
        String tenantCode = this.normalizeCode(tenantDto.getTenantCode());
        if (this.tenantRepository.findByTenantCode(tenantCode).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant code \"%s\" is already in use.", tenantCode));
        }
        Tenant tenant = new Tenant();
        tenant.setUuid(UUID.randomUUID().toString());
        tenant.setTenantName(tenantDto.getTenantName().trim());
        tenant.setTenantCode(tenantCode);
        tenant.setStatus(TenantStatus.Active);
        tenant.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.tenantRepository.save(tenant);
        return new ResponseDto(SUCCESS, String.format("Tenant \"%s\" created.", tenant.getTenantName()), this.mapToDto(tenant));
    }

    @Override
    public ResponseDto updateTenant(TenantDto tenantDto) throws Exception {
        if (isNull(tenantDto.getTenantId())) {
            return new ResponseDto(ERROR, "Tenant id missing.");
        } else if (isNull(tenantDto.getTenantName()) || tenantDto.getTenantName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Tenant name missing.");
        }
        Optional<Tenant> tenantOpt = this.tenantRepository.findById(tenantDto.getTenantId());
        if (!tenantOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", tenantDto.getTenantId()));
        }
        Tenant tenant = tenantOpt.get();
        tenant.setTenantName(tenantDto.getTenantName().trim());
        this.tenantRepository.save(tenant);
        return new ResponseDto(SUCCESS, String.format("Tenant \"%s\" updated.", tenant.getTenantName()), this.mapToDto(tenant));
    }

    @Override
    public ResponseDto changeTenantStatus(TenantDto tenantDto) throws Exception {
        if (isNull(tenantDto.getTenantId())) {
            return new ResponseDto(ERROR, "Tenant id missing.");
        } else if (isNull(tenantDto.getStatus())) {
            return new ResponseDto(ERROR, "Tenant status missing.");
        }
        Optional<Tenant> tenantOpt = this.tenantRepository.findById(tenantDto.getTenantId());
        if (!tenantOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", tenantDto.getTenantId()));
        }
        Tenant tenant = tenantOpt.get();
        tenant.setStatus(tenantDto.getStatus());
        this.tenantRepository.save(tenant);
        return new ResponseDto(SUCCESS, String.format("Tenant \"%s\" is now %s.", tenant.getTenantName(), tenant.getStatus()));
    }

    private String normalizeCode(String tenantCode) {
        return tenantCode.trim().toLowerCase().replaceAll("[^a-z0-9-]", "-");
    }

    private TenantDto mapToDto(Tenant tenant) {
        TenantDto dto = new TenantDto();
        dto.setTenantId(tenant.getTenantId());
        dto.setUuid(tenant.getUuid());
        dto.setTenantName(tenant.getTenantName());
        dto.setTenantCode(tenant.getTenantCode());
        dto.setStatus(tenant.getStatus());
        dto.setDateCreated(tenant.getDateCreated());
        return dto;
    }

}
