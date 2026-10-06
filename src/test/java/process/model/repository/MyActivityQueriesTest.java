package process.model.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile's activity card reads four queries and they must agree on what counts as the
 * person's work. The job counts left deleted jobs out; the two run queries did not, so a run of
 * a job deleted yesterday still appeared under "Recent runs", linking to a job that no longer
 * opens, and still counted in the seven-day tiles.
 *
 * @author Nabeel Ahmed
 */
public class MyActivityQueriesTest {

    private static String sqlOf(Class<?> repository, String method) {
        return Arrays.stream(repository.getDeclaredMethods())
            .filter(m -> m.getName().equals(method))
            .map(m -> m.getAnnotation(Query.class))
            .filter(q -> q != null)
            .map(Query::value)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no @Query on " + method));
    }

    @Test
    void everyActivityQueryLeavesDeletedJobsOut() {
        for (String method : new String[] {"findRecentRunsForAssignee", "countRecentRunsForAssignee"}) {
            assertThat(sqlOf(JobQueueRepository.class, method)).as(method).contains("j.job_status <> 'Delete'");
        }
        for (String method : new String[] {"countAssignedTo", "outcomesForAssignee"}) {
            assertThat(sqlOf(SourceJobRepository.class, method)).as(method).contains("job_status <> 'Delete'");
        }
    }
}
