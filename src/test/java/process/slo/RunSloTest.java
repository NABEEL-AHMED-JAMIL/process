package process.slo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-196: the pipeline execution SLI's classification, cell by cell -- every terminal status against every closing
 * path, and against no recorded path (a run that ended before V174). docs/SLO.md in etl-platform is the definition;
 * a change to it is a change to this table.
 */
class RunSloTest {

    private static final RunSlo G = RunSlo.GOOD;
    private static final RunSlo B = RunSlo.BAD;
    private static final RunSlo X = RunSlo.EXCLUDED;

    /** Rows: reason (null last). Columns: Completed, Failed, Interrupt, Skip, Missed. */
    private static final Map<RunEnd, RunSlo[]> TABLE = new EnumMap<>(RunEnd.class);
    private static final RunSlo[] UNRECORDED = {G, B, B, X, X};

    static {
        TABLE.put(RunEnd.WORKER,        new RunSlo[] {G, B, B, X, X});
        TABLE.put(RunEnd.DECLINED,      new RunSlo[] {G, B, B, X, X});
        TABLE.put(RunEnd.DISPATCH,      new RunSlo[] {G, B, B, X, X});
        TABLE.put(RunEnd.STALLED,       new RunSlo[] {G, B, B, X, X});
        TABLE.put(RunEnd.TOKEN_EXPIRED, new RunSlo[] {G, B, B, X, X});
        TABLE.put(RunEnd.REFUSED,       new RunSlo[] {X, X, X, X, X});
        TABLE.put(RunEnd.AI_STEP,       new RunSlo[] {X, X, X, X, X});
        TABLE.put(RunEnd.OPERATOR,      new RunSlo[] {X, X, X, X, X});
        TABLE.put(RunEnd.SKIPPED,       new RunSlo[] {X, X, X, X, X});
        TABLE.put(RunEnd.MISSED,        new RunSlo[] {X, X, X, X, X});
    }

    private static final JobStatus[] COLUMNS = {
        JobStatus.Completed, JobStatus.Failed, JobStatus.Interrupt, JobStatus.Skip, JobStatus.Missed };

    @Test
    void theTerminalStatusesAreExactlyTheOnesNotInFlight() {
        assertThat(RunSlo.TERMINAL).containsExactlyInAnyOrder(COLUMNS);
        for (JobStatus status : JobStatus.values()) {
            assertThat(RunSlo.isTerminal(status)).as(status.name()).isEqualTo(!status.isInFlight());
        }
        assertThat(RunSlo.isTerminal(null)).isFalse();
    }

    @Test
    void everyReasonHasARowInTheTable() {
        assertThat(TABLE.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(RunEnd.class));
    }

    @Test
    void everyCellIsClassifiedAsTheDefinitionSays() {
        for (Map.Entry<RunEnd, RunSlo[]> row : TABLE.entrySet()) {
            for (int c = 0; c < COLUMNS.length; c++) {
                assertThat(RunSlo.of(COLUMNS[c], row.getKey())).as(COLUMNS[c] + " / " + row.getKey()).isEqualTo(row.getValue()[c]);
            }
        }
        for (int c = 0; c < COLUMNS.length; c++) {
            assertThat(RunSlo.of(COLUMNS[c], null)).as(COLUMNS[c] + " / unrecorded").isEqualTo(UNRECORDED[c]);
        }
    }

    /** The two rules the board names: the stall sweep's Interrupt is a failure (C4); a refusal is not. */
    @Test
    void theSweepsInterruptIsBadAndARefusalIsNeither() {
        assertThat(RunSlo.of(JobStatus.Interrupt, RunEnd.STALLED)).isEqualTo(RunSlo.BAD);
        assertThat(RunSlo.of(JobStatus.Interrupt, RunEnd.TOKEN_EXPIRED)).isEqualTo(RunSlo.BAD);
        assertThat(RunSlo.of(JobStatus.Failed, RunEnd.AI_STEP)).isEqualTo(RunSlo.EXCLUDED);
        assertThat(RunSlo.of(JobStatus.Failed, RunEnd.REFUSED)).isEqualTo(RunSlo.EXCLUDED);
        // MIG-201's decline (Start -> Failed) counts against the SLI: flagged in docs/SLO.md.
        assertThat(RunSlo.of(JobStatus.Failed, RunEnd.DECLINED)).isEqualTo(RunSlo.BAD);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = {"Queue", "Start", "Running"})
    void aRunInFlightHasNoClass(JobStatus status) {
        assertThatThrownBy(() -> RunSlo.of(status, RunEnd.WORKER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWorkersFailureBeforeItEverRanIsADecline() {
        assertThat(RunEnd.reportedBy(JobStatus.Start, JobStatus.Failed)).isEqualTo(RunEnd.DECLINED);
        assertThat(RunEnd.reportedBy(JobStatus.Queue, JobStatus.Failed)).isEqualTo(RunEnd.DECLINED);
        assertThat(RunEnd.reportedBy(JobStatus.Running, JobStatus.Failed)).isEqualTo(RunEnd.WORKER);
        assertThat(RunEnd.reportedBy(JobStatus.Running, JobStatus.Completed)).isEqualTo(RunEnd.WORKER);
        assertThat(RunEnd.reportedBy(JobStatus.Start, JobStatus.Completed)).isEqualTo(RunEnd.WORKER);
        assertThat(RunEnd.reportedBy(null, JobStatus.Failed)).isEqualTo(RunEnd.WORKER);
    }

    @Test
    void tagsAreLowerCaseNames() {
        assertThat(RunEnd.TOKEN_EXPIRED.tag()).isEqualTo("token_expired");
        assertThat(RunSlo.EXCLUDED.tag()).isEqualTo("excluded");
    }
}
