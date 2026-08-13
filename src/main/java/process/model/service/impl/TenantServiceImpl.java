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

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;

    public TenantServiceImpl(TenantRepository tenantRepository, AppUserRepository appUserRepository) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
    }

    @Override
    public ResponseDto listTenants() throws Exception {
        List<Tenant> tenants = this.tenantRepository.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete);
        List<TenantDto> tenantDtos = tenants.stream().map(tenant -> {
            TenantDto dto = this.mapToDto(tenant);
            dto.setUserCount(this.appUserRepository.countByTenantIdAndStatusNot(tenant.getTenantId(), Status.Delete));
            return dto;
        }).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Tenants fetched successfully.", tenantDtos);
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
