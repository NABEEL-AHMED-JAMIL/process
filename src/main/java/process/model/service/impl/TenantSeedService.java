package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.*;
import javax.annotation.PostConstruct;
import java.sql.Timestamp;
import java.util.UUID;

@Component
public class TenantSeedService {

    private final Logger logger = LoggerFactory.getLogger(TenantSeedService.class);

    private static final String PLATFORM_ADMIN_USERNAME = "admin@platform.local";
    private static final String PLATFORM_ADMIN_DEFAULT_PASSWORD = "admin";
    private static final String DEFAULT_TENANT_CODE = "default";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final DynamicFormRepository dynamicFormRepository;
    private final AiAgentRepository aiAgentRepository;
    private final PdfHighlighterTaskRepository pdfHighlighterTaskRepository;
    private final LookupDataRepository lookupDataRepository;
    private final LookupDataCacheService lookupDataCacheService;

    public TenantSeedService(TenantRepository tenantRepository, AppUserRepository appUserRepository,
        PasswordEncoder passwordEncoder, SourceJobRepository sourceJobRepository,
        SourceTaskRepository sourceTaskRepository, DynamicFormRepository dynamicFormRepository,
        AiAgentRepository aiAgentRepository, PdfHighlighterTaskRepository pdfHighlighterTaskRepository,
        LookupDataRepository lookupDataRepository, LookupDataCacheService lookupDataCacheService) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.dynamicFormRepository = dynamicFormRepository;
        this.aiAgentRepository = aiAgentRepository;
        this.pdfHighlighterTaskRepository = pdfHighlighterTaskRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.lookupDataCacheService = lookupDataCacheService;
    }

    @PostConstruct
    public void seed() {
        try {
            Tenant defaultTenant = this.ensureDefaultTenant();
            AppUser platformAdmin = this.ensurePlatformAdmin();
            this.backfillTenantIds(defaultTenant.getTenantId());
            this.backfillAssignedUserIds(platformAdmin.getAppUserId());

            this.lookupDataCacheService.initializeCache();
        } catch (Exception ex) {
            this.logger.error("An error occurred while seeding tenant foundation: {}", ex.getMessage(), ex);
        }
    }

    private Tenant ensureDefaultTenant() {
        return this.tenantRepository.findByTenantCode(DEFAULT_TENANT_CODE)
            .orElseGet(() -> {
                Tenant tenant = new Tenant();
                tenant.setUuid(UUID.randomUUID().toString());
                tenant.setTenantName("Default");
                tenant.setTenantCode(DEFAULT_TENANT_CODE);
                tenant.setStatus(TenantStatus.Active);
                tenant.setDateCreated(new Timestamp(System.currentTimeMillis()));
                Tenant saved = this.tenantRepository.save(tenant);
                this.logger.info("=========Seeded Default tenant with id {} ==========", saved.getTenantId());
                return saved;
            });
    }

    private AppUser ensurePlatformAdmin() {
        return this.appUserRepository.findByUsernameAndStatusNot(PLATFORM_ADMIN_USERNAME, Status.Delete)
            .orElseGet(() -> {
                AppUser admin = new AppUser();
                admin.setUuid(UUID.randomUUID().toString());
                admin.setTenantId(null);
                admin.setUsername(PLATFORM_ADMIN_USERNAME);
                admin.setPassword(this.passwordEncoder.encode(PLATFORM_ADMIN_DEFAULT_PASSWORD));
                admin.setFullName("Platform Admin");
                admin.setUserRole(UserRole.PLATFORM_ADMIN);
                admin.setStatus(Status.Active);
                admin.setDateCreated(new Timestamp(System.currentTimeMillis()));
                AppUser saved = this.appUserRepository.save(admin);
                this.logger.info("=========Seeded Platform Admin user '{}' -- change its password after first login ==========",
                    PLATFORM_ADMIN_USERNAME);
                return saved;
            });
    }

    private void backfillTenantIds(Long defaultTenantId) {
        int jobs = this.sourceJobRepository.backfillTenantId(defaultTenantId);
        int tasks = this.sourceTaskRepository.backfillTenantId(defaultTenantId);
        int forms = this.dynamicFormRepository.backfillTenantId(defaultTenantId);
        int agents = this.aiAgentRepository.backfillTenantId(defaultTenantId);
        int pdfTasks = this.pdfHighlighterTaskRepository.backfillTenantId(defaultTenantId);
        int buckets = this.lookupDataRepository.backfillBucketTenantId(defaultTenantId);
        int total = jobs + tasks + forms + agents + pdfTasks + buckets;
        if (total > 0) {
            this.logger.info("=========Backfilled tenantId to Default tenant ({}) on {} pre-existing row(s): "
                + "sourceJob={}, sourceTask={}, dynamicForm={}, aiAgent={}, pdfHighlighterTask={}, bucketLookup={} ==========",
                defaultTenantId, total, jobs, tasks, forms, agents, pdfTasks, buckets);
        }
    }

    private void backfillAssignedUserIds(Long platformAdminId) {
        int jobs = this.sourceJobRepository.backfillAssignedUserId(platformAdminId);
        if (jobs > 0) {
            this.logger.info("=========Backfilled assignedUserId to Platform Admin ({}) on {} pre-existing SourceJob row(s) "
                + "-- reassign to the right owner via the UI once one exists ==========", platformAdminId, jobs);
        }
    }

}
