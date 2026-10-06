package process.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.barco.platform.correlation.CorrelatedTaskScheduler;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * X5 in process (16-testing-strategy 8.3): Core's @Scheduled methods -- the enqueuer, the dispatcher, the
 * pre-dispatch sweep, the reconciler, the audit sync, the receipt purge -- each tick under a fresh id with
 * cronName and cronRunId in the MDC, and @Async work runs under its caller's id. Wired through Boot's own
 * builders, so spring.task.scheduling.pool.size (one thread per cron) still applies.
 */
class SchedulingCorrelationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class, TaskExecutionAutoConfiguration.class))
        .withUserConfiguration(SchedulingCorrelation.class, Ticking.class)
        .withPropertyValues("spring.task.scheduling.pool.size=5");

    @Configuration
    @EnableScheduling
    static class Ticking {
        final List<String> seen = new CopyOnWriteArrayList<>();
        final CountDownLatch ticked = new CountDownLatch(2);

        @Bean
        Ticking.Cron cron() {
            return new Cron(this);
        }

        static class Cron {
            private final Ticking owner;

            Cron(Ticking owner) {
                this.owner = owner;
            }

            @Scheduled(fixedDelay = 10)
            public void sweep() {
                this.owner.seen.add(CorrelationId.current() + "|" + MDC.get(CorrelatedTaskScheduler.CRON_NAME));
                this.owner.ticked.countDown();
            }
        }
    }

    @Test
    void theApplicationsSchedulerIsTheCorrelatedOneWithBootsPoolSize() {
        this.context.run(app -> {
            ThreadPoolTaskScheduler scheduler = app.getBean(ThreadPoolTaskScheduler.class);
            assertThat(scheduler).isInstanceOf(CorrelatedTaskScheduler.class);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(5);
        });
    }

    @Test
    void everyTickOfAScheduledMethodHasItsOwnIdAndName() {
        this.context.run(app -> {
            Ticking ticking = app.getBean(Ticking.class);
            assertThat(ticking.ticked.await(5, TimeUnit.SECONDS)).isTrue();
            String first = ticking.seen.get(0);
            String second = ticking.seen.get(1);
            assertThat(CorrelationId.isAcceptable(first.split("\\|")[0])).isTrue();
            assertThat(first).endsWith("|Cron.sweep");
            assertThat(first.split("\\|")[0]).isNotEqualTo(second.split("\\|")[0]);
        });
    }

    /** A scheduler bean would otherwise make Boot skip its @Async executor, and @Async would run on cron threads. */
    @Test
    void bootsAsyncExecutorIsStillThereAndIsNotTheScheduler() {
        this.context.run(app -> {
            assertThat(app).hasBean("applicationTaskExecutor").hasBean("taskExecutor");
            assertThat(app.getBean("taskExecutor")).isInstanceOf(ThreadPoolTaskExecutor.class)
                .isNotInstanceOf(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    void asyncWorkRunsUnderItsCallersId() {
        this.context.run(app -> {
            ThreadPoolTaskExecutor async = app.getBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class);
            CorrelationId.set("console-7f3a9c21");
            try {
                Future<String> seen = async.submit(CorrelationId::current);
                assertThat(seen.get(5, TimeUnit.SECONDS)).isEqualTo("console-7f3a9c21");
            } finally {
                CorrelationId.clear();
            }
        });
    }
}
