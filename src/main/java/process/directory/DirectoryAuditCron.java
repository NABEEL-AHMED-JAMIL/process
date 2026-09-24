package process.directory;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The nightly audits of the demoted keys (MIG-166, MIG-153), one instance at a time. Each logs what it found
 * at WARN; a failure of one does not stop the other.
 *
 * @author Nabeel Ahmed
 */
@Component
public class DirectoryAuditCron {

    private static final Logger logger = LoggerFactory.getLogger(DirectoryAuditCron.class);

    private final TenantOrphanAudit tenants;
    private final UserDirectoryReconciliation people;

    public DirectoryAuditCron(TenantOrphanAudit tenants, UserDirectoryReconciliation people) {
        this.tenants = tenants;
        this.people = people;
    }

    @Scheduled(cron = "${directory.audit.cron:0 40 2 * * *}")
    @SchedulerLock(name = "auditDemotedIdentityKeys", lockAtLeastFor = "30S", lockAtMostFor = "30M")
    public void audit() {
        try {
            this.tenants.run();
        } catch (RuntimeException failed) {
            logger.warn("Tenant orphan audit failed: {}", failed.getMessage());
        }
        try {
            this.people.run();
        } catch (RuntimeException failed) {
            logger.warn("User directory reconciliation failed: {}", failed.getMessage());
        }
    }
}
