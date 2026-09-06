package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.util.Optional;
import java.util.UUID;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class TenantSeedService {

    private final Logger logger = LoggerFactory.getLogger(TenantSeedService.class);

    private static final String PLATFORM_ADMIN_USERNAME = "admin@platform.local";
    // Public: TaskFormServiceImpl also targets this tenant, for a platform admin's new form --
    // see the comment on that call site for why.
    public static final String DEFAULT_TENANT_CODE = "default";

    private final TenantRepository tenantRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final SourceJobRepository sourceJobRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final AiAgentRepository aiAgentRepository;
    private final PdfHighlighterTaskRepository pdfHighlighterTaskRepository;
    private final LookupDataRepository lookupDataRepository;
    private final LookupDataCacheService lookupDataCacheService;

    @Value("${platform.admin.bootstrap-password:}")
    private String platformAdminBootstrapPassword;

    public TenantSeedService(TenantRepository tenantRepository, AppUserRepository appUserRepository,
        PasswordEncoder passwordEncoder, SourceJobRepository sourceJobRepository,
        SourceTaskRepository sourceTaskRepository,
        AiAgentRepository aiAgentRepository, PdfHighlighterTaskRepository pdfHighlighterTaskRepository,
        LookupDataRepository lookupDataRepository, LookupDataCacheService lookupDataCacheService) {
        this.tenantRepository = tenantRepository;
        this.appUserRepository = appUserRepository;
        this.passwordEncoder = passwordEncoder;
        this.sourceJobRepository = sourceJobRepository;
        this.sourceTaskRepository = sourceTaskRepository;
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
            // No platform admin means no bootstrap password was configured; there is nobody to
            // hand the orphaned jobs to, so leave them for the next boot that has one.
            if (platformAdmin != null) {
                this.backfillAssignedUserIds(platformAdmin.getAppUserId());
            }

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

    /**
     * The first password used to be the compiled-in string "admin", so every instance that was
     * deployed and never rotated it answered to a credential anyone could read off this file.
     * It now comes from configuration with no default: with nothing configured there is simply
     * no account to guess at, and the one that does get created cannot be used until whoever
     * takes it over changes the password.
     *
     * Returns null when nothing was seeded, which is not an error -- it is the safe outcome.
     */
    private AppUser ensurePlatformAdmin() {
        Optional<AppUser> existing = this.appUserRepository.findByUsernameAndStatusNot(PLATFORM_ADMIN_USERNAME, Status.Delete);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (this.platformAdminBootstrapPassword == null || this.platformAdminBootstrapPassword.trim().isEmpty()) {
            this.logger.error("=========No Platform Admin seeded: platform.admin.bootstrap-password is not set. "
                + "Set it and restart to create '{}' ==========", PLATFORM_ADMIN_USERNAME);
            return null;
        }
        AppUser admin = new AppUser();
        admin.setUuid(UUID.randomUUID().toString());
        admin.setTenantId(null);
        admin.setUsername(PLATFORM_ADMIN_USERNAME);
        admin.setPassword(this.passwordEncoder.encode(this.platformAdminBootstrapPassword));
        admin.setFullName("Platform Admin");
        admin.setUserRole(UserRole.PLATFORM_ADMIN);
        admin.setStatus(Status.Active);
        admin.setMustChangePassword(true);
        admin.setDateCreated(new Timestamp(System.currentTimeMillis()));
        AppUser saved = this.appUserRepository.save(admin);
        this.logger.info("=========Seeded Platform Admin user '{}' -- it must change its password on first login ==========",
            PLATFORM_ADMIN_USERNAME);
        return saved;
    }

    private void backfillTenantIds(Long defaultTenantId) {
        int jobs = this.sourceJobRepository.backfillTenantId(defaultTenantId);
        int tasks = this.sourceTaskRepository.backfillTenantId(defaultTenantId);
        int agents = this.aiAgentRepository.backfillTenantId(defaultTenantId);
        int pdfTasks = this.pdfHighlighterTaskRepository.backfillTenantId(defaultTenantId);
        int buckets = this.lookupDataRepository.backfillBucketTenantId(defaultTenantId);
        int total = jobs + tasks + agents + pdfTasks + buckets;
        if (total > 0) {
            this.logger.info("=========Backfilled tenantId to Default tenant ({}) on {} pre-existing row(s): "
                + "sourceJob={}, sourceTask={}, aiAgent={}, pdfHighlighterTask={}, bucketLookup={} ==========",
                defaultTenantId, total, jobs, tasks, agents, pdfTasks, buckets);
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
