package process.config;

import org.barco.platform.correlation.CorrelatedTaskScheduler;
import org.barco.platform.correlation.CorrelationTaskDecorator;
import org.springframework.boot.task.TaskExecutorBuilder;
import org.springframework.boot.task.TaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * X5 (16-testing-strategy 8.3; MIG-94): Core's @Scheduled methods tick under a fresh correlation id each time,
 * with cronName and cronRunId in the MDC -- the enqueuer, the dispatcher, the pre-dispatch sweep, the reconciler,
 * the audit sync and the receipt purge -- and @Async work runs under its caller's id.
 *
 * The scheduler is built by Boot's own builder, so spring.task.scheduling.pool.size (one thread per cron, see
 * application.properties) and the thread-name prefix still apply; the TaskDecorator bean is what Boot's
 * applicationTaskExecutor picks up for @Async. ShedLock locks inside the scheduled method (PROXY_METHOD), so it is
 * unaffected: a tick that does not get the lock is still one tick, logged under its own id.
 *
 * @author Nabeel Ahmed
 */
@Configuration
public class SchedulingCorrelation {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler(TaskSchedulerBuilder builder) {
        return builder.configure(new CorrelatedTaskScheduler());
    }

    @Bean
    public TaskDecorator correlationTaskDecorator() {
        return new CorrelationTaskDecorator();
    }

    /**
     * Boot's own @Async and MVC-async executor, declared as Boot declares it. Boot backs off from creating it once
     * any Executor exists (TaskExecutionAutoConfiguration is @ConditionalOnMissingBean(Executor.class)), and the
     * scheduler above is one; without this, @Async work would run on the cron threads. Boot's builder already
     * carries the TaskDecorator bean.
     */
    @Lazy
    @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
    public ThreadPoolTaskExecutor applicationTaskExecutor(TaskExecutorBuilder builder) {
        return builder.build();
    }
}
