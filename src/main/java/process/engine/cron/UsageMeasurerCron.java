package process.engine.cron;

import java.util.stream.Collectors;
import process.storage.remote.RemoteStorageDirectory;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.billing.MeterClient;
import process.billing.Meter;
import process.billing.UsageEvent;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.pojo.StorageConnection;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * What a workspace holds, measured once a night: bytes kept in each of its buckets, the
 * users it has, the topics it runs. Operations are reported as they happen; these three are
 * states, not events, so somebody has to look.
 *
 * Every event's dedupe key is the day, so the measurer can run twice and count once, and a
 * missed night is a missed night rather than a double one. Storage is charged as 24 GB-hours
 * per GB seen at 02:00 -- a file that existed then is a day of storage whether or not it was
 * deleted at 09:00, which is the rule the design states to the customer.
 */
@Component
public class UsageMeasurerCron {

    private static final Logger logger = LoggerFactory.getLogger(UsageMeasurerCron.class);

    private final MeterClient meter;
    private final TenantRepository tenants;
    /** Storage's directory and its measurement (MIG-193): the buckets are listed inside storage-service. */
    private final RemoteStorageDirectory storage;
    private final AppUserRepository users;
    private final SourceTaskTypeRepository topics;

    public UsageMeasurerCron(MeterClient meter, TenantRepository tenants, RemoteStorageDirectory storage,
        AppUserRepository users, SourceTaskTypeRepository topics) {
        this.meter = meter; this.tenants = tenants; this.storage = storage;
        this.users = users; this.topics = topics;
    }

    /** A nightly measurement stands for the whole day: GB-hours and topic-hours are the count times this. */
    private static final int HOURS_PER_DAY = 24;

    @Scheduled(cron = "${meter.measure.cron:0 0 2 * * *}")
    @SchedulerLock(name = "measureUsage", lockAtLeastFor = "1M", lockAtMostFor = "2H")
    public void measureNightly() {
        this.measure(LocalDate.now());
    }

    /** One night's measurement for every active workspace. Public so an operator can ask for it. */
    public int measure(LocalDate day) {
        if (!this.meter.isConfigured()) {
            return 0;
        }
        int events = 0;
        for (Tenant tenant : this.tenants.findByStatusNotOrderByTenantIdDesc(TenantStatus.Delete)) {
            if (tenant.getStatus() != TenantStatus.Active) {
                continue;
            }
            try {
                events += this.measureTenant(tenant.getTenantId(), day);
            } catch (RuntimeException ex) {
                logger.warn("meter: measurement for tenant {} failed: {}", tenant.getTenantId(), ex.toString());
            }
        }
        logger.info("meter: nightly measurement for {} reported {} event(s)", day, events);
        return events;
    }

    int measureTenant(Long tenantId, LocalDate day) {
        int events = 0;
        Instant at = day.atTime(2, 0).toInstant(ZoneOffset.UTC);
        // Storage kept: every object store connection the workspace owns, listed whole.
        List<StorageConnection> active = this.storage.workspace(tenantId).stream()
            .filter(c -> c.getStatus() == Status.Active).collect(Collectors.toList());
        for (StorageConnection connection : active) {
            if (connection.getProvider() == null || !connection.getProvider().isObjectStore() || connection.getBucketName() == null) {
                continue;
            }
            long bytes = this.bytesIn(connection);
            if (bytes < 0) {
                continue;
            }
            this.meter.report(UsageEvent.of(tenantId, Meter.STORAGE_GB_HOURS, UsageEvent.gb(bytes) * HOURS_PER_DAY,
                    "measure#" + tenantId + "#storage#" + connection.getStorageConnectionId() + "#" + day)
                .subject("bucket", connection.getAlias()).source("measurer").at(at).note(bytes + " bytes at 02:00"));
            events++;
        }
        // Seats: every user who is not deleted, one user-day each.
        long seats = this.users.countByTenantIdAndStatusNot(tenantId, Status.Delete);
        if (seats > 0) {
            this.meter.report(UsageEvent.of(tenantId, Meter.SEATS_USER_DAYS, seats, "measure#" + tenantId + "#seats#" + day)
                .source("measurer").at(at));
            events++;
        }
        // Topics the workspace runs -- active, with a live task on them -- 24 topic-hours each.
        long topicCount = this.topics.countTopicsInUse(tenantId);
        if (topicCount > 0) {
            this.meter.report(UsageEvent.of(tenantId, Meter.KAFKA_TOPIC_HOURS, topicCount * HOURS_PER_DAY, "measure#" + tenantId + "#topics#" + day)
                .source("measurer").at(at));
            events++;
        }
        return events;
    }

    /**
     * Total bytes in a connection's bucket, measured inside Storage (MIG-193): the partial sum past the
     * object cap, and -1 when it was not or could not be measured -- which skips it, never bills zero.
     */
    long bytesIn(StorageConnection connection) {
        return this.storage.bytesIn(connection);
    }
}
