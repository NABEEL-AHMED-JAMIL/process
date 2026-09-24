package process.model.enums;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import process.model.repository.JobQueueRepository;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.assertj.core.api.Assertions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every place that decides whether a run is still occupying the queue agrees on which statuses
 * mean that.
 *
 * The set was written out four times and one copy -- "Run now" and "Skip next" -- left out Start,
 * so a freshly dispatched job could be run again by hand into the same output folder. Java callers
 * now share {@link JobStatus#IN_FLIGHT}. Two native queries cannot reference a Java constant, so
 * this holds their literal lists to it: widen or narrow the set and this names the query that
 * still says otherwise, instead of the next drift being found in production.
 */
class JobStatusInFlightTest {

    private static final Pattern IN_LIST = Pattern.compile("in\\s*\\(([^)]*)\\)");

    @Test
    void theSetIsQueueStartAndRunning() {
        assertThat(JobStatus.IN_FLIGHT)
            .containsExactlyInAnyOrder(JobStatus.Queue, JobStatus.Start, JobStatus.Running);
    }

    @Test
    void everyTerminalStatusIsOutsideIt() {
        for (JobStatus status : JobStatus.values()) {
            boolean terminal = status == JobStatus.Failed || status == JobStatus.Completed
                || status == JobStatus.Skip || status == JobStatus.Interrupt
                || status == JobStatus.Missed;
            assertThat(status.isInFlight()).as(status.name()).isEqualTo(!terminal);
        }
    }

    @Test
    void theSetCannotBeChangedAtRuntime() {
        Assertions.assertThatThrownBy(() -> JobStatus.IN_FLIGHT.add(JobStatus.Failed))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theDispatchersBusyCountNamesExactlyTheSet() throws Exception {
        Method method = JobQueueRepository.class.getMethod("getCountForInQueueJobByJobId", Long.class);
        assertThat(statusesNamedIn(method)).isEqualTo(expected());
    }

    @Test
    void theStallSweepNamesExactlyTheSet() throws Exception {
        Method method = JobQueueRepository.class.getMethod("findStalledRuns", LocalDateTime.class);
        assertThat(statusesNamedIn(method)).isEqualTo(expected());
    }

    /** MIG-63: the note on a refused report, and the sweep that acts on it, guard on the same set. */
    @Test
    void theRefusedCallbackNoteAndItsSweepNameExactlyTheSet() throws Exception {
        assertThat(statusesNamedIn(JobQueueRepository.class.getMethod("noteRefusedCallback",
            Long.class, LocalDateTime.class, String.class))).isEqualTo(expected());
        assertThat(statusesNamedIn(JobQueueRepository.class.getMethod("findRunsWithRefusedCallbacks")))
            .isEqualTo(expected());
    }

    /**
     * P12 (MIG-135): the set is enforced by ux_job_queue_one_in_flight_per_job, and that index's
     * predicate is held here to the same set. An index narrower than the set lets a second run in; one
     * wider than it blocks a job on a run that is over.
     */
    @Test
    void theOneInFlightIndexNamesExactlyTheSet() throws Exception {
        String changeset = new String(Files.readAllBytes(Paths.get("src/main/resources/db/changelog/changelog-sets/"
            + "V83.0-one-in-flight-run-per-job/V83__one_in_flight_run_per_job.sql")), StandardCharsets.UTF_8);
        String index = changeset.substring(changeset.indexOf("CREATE UNIQUE INDEX ux_job_queue_one_in_flight_per_job"));
        assertThat(statusesNamedIn(index)).isEqualTo(expected());
    }

    /** And no query on job_queue names an in-flight list of its own that has drifted from it. */
    @Test
    void everyJobQueueQueryThatNamesQueueNamesTheWholeSet() {
        int checked = 0;
        for (Method method : JobQueueRepository.class.getMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null) {
                continue;
            }
            Matcher matcher = IN_LIST.matcher(query.value().toLowerCase(Locale.ROOT));
            while (matcher.find()) {
                if (matcher.group(1).contains("'queue'")) {
                    checked++;
                    assertThat(statusesIn(matcher.group(1))).as(method.getName()).isEqualTo(expected());
                }
            }
        }
        assertThat(checked).as("queries naming the in-flight set").isGreaterThanOrEqualTo(4);
    }

    private static Set<String> expected() {
        return JobStatus.IN_FLIGHT.stream()
            .map(status -> status.name().toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> statusesNamedIn(Method method) {
        return statusesNamedIn(method.getAnnotation(Query.class).value());
    }

    private static Set<String> statusesNamedIn(String query) {
        Matcher matcher = IN_LIST.matcher(query.toLowerCase(Locale.ROOT));
        assertThat(matcher.find()).as("no IN (...) list in %s", query).isTrue();
        return statusesIn(matcher.group(1));
    }

    private static Set<String> statusesIn(String list) {
        return Arrays.stream(list.split(","))
            .map(token -> token.trim().replace("'", "").toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
