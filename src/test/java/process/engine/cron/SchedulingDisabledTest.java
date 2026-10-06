package process.engine.cron;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import process.engine.ProducerBulkEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * That `process.scheduling.enabled=false` actually switches the scheduled work off.
 *
 * It did not. src/test/resources/application-e2e.properties had set that property since the E2E
 * profile was written and no code anywhere read it, so every end-to-end run booted the whole
 * application on a developer's machine with @EnableScheduling live. Five seconds in, ProcessCron
 * enqueued and started REAL DUE JOBS out of the shared database: one run dispatched twelve of a
 * tenant's jobs and marked every one of them Failed, because a test context has no worker for the
 * dispatch to reach. The same run wrote their last_job_run in the developer's own timezone while
 * the container writes UTC, leaving that column five hours inconsistent with itself.
 *
 * A property that is set and read by nobody is indistinguishable from one that works, which is why
 * this is asserted rather than assumed.
 *
 * @author Nabeel Ahmed
 */
public class SchedulingDisabledTest {

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
        .withBean(ProducerBulkEngine.class, () -> mock(ProducerBulkEngine.class))
        .withUserConfiguration(ProcessCron.class);

    @Test
    void theJobSchedulerIsNotCreatedWhenSchedulingIsTurnedOff() {
        this.contexts.withPropertyValues("process.scheduling.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ProcessCron.class));
    }

    @Test
    void itIsCreatedWhenTheFlagSaysSo() {
        this.contexts.withPropertyValues("process.scheduling.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(ProcessCron.class));
    }

    /** Production sets nothing, and must keep scheduling. An opt-out that defaults to off would
     *  silently stop every job on the platform from ever running. */
    @Test
    void anAbsentFlagMeansEnabled() {
        this.contexts.run(context -> assertThat(context).hasSingleBean(ProcessCron.class));
    }
}
