package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.billing.BillingPort;
import process.billing.WorkspaceZones;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.util.BusinessTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-347: what a run reads as {{param.*}} and {{run.*}} -- a backfill's own, a scheduled slot's, a hand-started run's. */
class RunValuesResolverTest {

    static final long TENANT = 9100L;

    /** Billing that names this zone for every workspace. */
    static WorkspaceZones zones(String zone) {
        return new WorkspaceZones(new BillingPort() {
            @Override
            public String zoneOf(long tenantId) {
                return zone;
            }

            @Override
            public RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> jobQueueIds) {
                throw new UnsupportedOperationException();
            }
        });
    }

    static SourceJob job() {
        SourceJob job = new SourceJob();
        job.setJobId(51L);
        job.setTenantId(TENANT);
        return job;
    }

    static JobQueue run() {
        JobQueue run = new JobQueue();
        run.setJobQueueId(7001L);
        run.setJobId(51L);
        run.setTenantId(TENANT);
        return run;
    }

    static PipelineDefinition definition() {
        PipelineDefinition.Parameter region = PipelineDefinition.Parameter.of("region", "choice");
        region.setChoices(Arrays.asList("north", "south"));
        region.setDefaultValue("north");
        PipelineDefinition definition = new PipelineDefinition();
        definition.setParameters(Collections.singletonList(region));
        return definition;
    }

    @AfterEach
    void systemClock() {
        BusinessTime.useSystemClock();
    }

    @Test
    void aBackfillRunKeepsItsOwnPeriodAndItsValues() {
        JobQueue run = run();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("region", "south");
        run.setRunParams(RunParameters.RunValues.ofDay(params, LocalDate.parse("2026-10-02"), ZoneId.of("America/Chicago")).toJson());
        RunValuesResolver.Outcome out = new RunValuesResolver(zones("Asia/Kolkata"), jobId -> "Daily").resolve(job(), run, definition());
        assertThat(out.refusal).isNull();
        assertThat(out.values.placeholders()).containsEntry("param.region", "south").containsEntry("run.date", "2026-10-02")
            .containsEntry("run.windowStart", "2026-10-02T00:00:00-05:00").containsEntry("run.windowEnd", "2026-10-03T00:00:00-05:00");
    }

    @Test
    void aScheduledRunIsForItsSlotsDayInTheWorkspacesZoneWithTheDefaults() {
        JobQueue run = run();
        // 21:30 Chicago on 1 October is 08:00 on 2 October in Kolkata.
        run.setRunSlotAt(LocalDateTime.parse("2026-10-01T21:30:00"));
        RunValuesResolver.Outcome chicago = new RunValuesResolver(zones("America/Chicago"), jobId -> "Daily").resolve(job(), run, definition());
        assertThat(chicago.values.date).isEqualTo("2026-10-01");
        assertThat(chicago.values.params).containsEntry("region", "north");
        RunValuesResolver.Outcome kolkata = new RunValuesResolver(zones("Asia/Kolkata"), jobId -> "Daily").resolve(job(), run, definition());
        assertThat(kolkata.values.date).isEqualTo("2026-10-02");
        assertThat(kolkata.values.windowStart).isEqualTo("2026-10-02T00:00:00+05:30");
    }

    @Test
    void aMinuteOrHourlyScheduleIsForItsSlotsHour() {
        JobQueue run = run();
        run.setRunSlotAt(LocalDateTime.parse("2026-10-01T09:15:00"));
        RunValuesResolver.Outcome out = new RunValuesResolver(zones("America/Chicago"), jobId -> "Hr").resolve(job(), run, definition());
        assertThat(out.values.windowStart).isEqualTo("2026-10-01T09:00:00-05:00");
        assertThat(out.values.windowEnd).isEqualTo("2026-10-01T10:00:00-05:00");
    }

    @Test
    void aRunStartedByHandIsForTodayInTheZone() {
        BusinessTime.useClock(Clock.fixed(Instant.parse("2026-10-13T03:00:00Z"), BusinessTime.ZONE));
        RunValuesResolver.Outcome chicago = new RunValuesResolver(zones("America/Chicago"), jobId -> null).resolve(job(), run(), definition());
        assertThat(chicago.values.date).isEqualTo("2026-10-12");
        RunValuesResolver.Outcome kolkata = new RunValuesResolver(zones("Asia/Kolkata"), jobId -> null).resolve(job(), run(), definition());
        assertThat(kolkata.values.date).isEqualTo("2026-10-13");
    }

    @Test
    void aRequiredParameterWithNoValueOrValuesThatNoLongerStandRefuseTheRun() {
        PipelineDefinition definition = definition();
        PipelineDefinition.Parameter since = PipelineDefinition.Parameter.of("since", "date");
        since.setRequired(true);
        definition.setParameters(Arrays.asList(definition.getParameters().get(0), since));
        RunValuesResolver.Outcome none = new RunValuesResolver(zones("America/Chicago"), jobId -> null).resolve(job(), run(), definition);
        assertThat(none.refusal).isEqualTo("Its parameters do not stand: since: required, and it has no default.");
        JobQueue changed = run();
        changed.setRunParams("{\"params\":{\"region\":\"east\",\"since\":\"2026-10-01\"}}");
        assertThat(new RunValuesResolver(zones("America/Chicago"), jobId -> null).resolve(job(), changed, definition).refusal)
            .isEqualTo("Its parameters do not stand: region: one of north, south.");
    }

    @Test
    void billingNotAnsweringIsTheBusinessZone() {
        WorkspaceZones silent = new WorkspaceZones(new BillingPort() {
            @Override
            public String zoneOf(long tenantId) {
                throw new BillingUnavailable("down", null);
            }

            @Override
            public RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> jobQueueIds) {
                throw new UnsupportedOperationException();
            }
        });
        assertThat(silent.zoneOf(TENANT)).isEqualTo(BusinessTime.ZONE);
        assertThat(silent.zoneOf(null)).isEqualTo(BusinessTime.ZONE);
    }
}
