package process.model.enums;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import process.model.repository.JobQueueRepository;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> JobStatus.IN_FLIGHT.add(JobStatus.Failed))
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

    private static Set<String> expected() {
        return JobStatus.IN_FLIGHT.stream()
            .map(status -> status.name().toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> statusesNamedIn(Method method) {
        String sql = method.getAnnotation(Query.class).value().toLowerCase(Locale.ROOT);
        Matcher matcher = IN_LIST.matcher(sql);
        assertThat(matcher.find()).as("no IN (...) list in %s", method.getName()).isTrue();
        return Arrays.stream(matcher.group(1).split(","))
            .map(token -> token.trim().replace("'", "").toUpperCase(Locale.ROOT))
            .collect(Collectors.toCollection(TreeSet::new));
    }
}
