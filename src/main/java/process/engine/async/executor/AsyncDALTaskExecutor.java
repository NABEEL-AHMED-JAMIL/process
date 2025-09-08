package process.engine.async.executor;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import process.util.exception.ExceptionUtil;
import java.util.concurrent.*;

/**
 * @author Nabeel Ahmed
 */
public class AsyncDALTaskExecutor {

    public static Logger logger = LogManager.getLogger(AsyncDALTaskExecutor.class);

    private static ThreadPoolTaskExecutor threadPoolTask;
    private static ScheduledExecutorService scheduler;

    /**
     * This method use to add the task in thread pool
     * @param task
     * @param priority
     * */
    public static void addTask(Runnable task, Integer priority) {
        try {
            logger.info("Submitting Task of type :- {}.", task.getClass().getCanonicalName());
            PrioritizedTask prioritizedTask = new PrioritizedTask(task, priority);
            threadPoolTask.execute(prioritizedTask);
        } catch (RejectedExecutionException ex) {
            logger.error("Failed to submit Task in queue :- {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * If max threads reach the limit the new thread reject state so its depend on the requirement
     * either rejected thread add again into the thread or else save in the db with the as inQueue status
     * @param corePoolSize
     * @param maxPoolSize
     * @param queueCapacity
     * @param keepAlive
     * */
    public AsyncDALTaskExecutor(Integer corePoolSize, Integer maxPoolSize, Integer queueCapacity, Integer keepAlive) {
        logger.info(">============AsyncDALTaskExecutor Start Successful============<");
        threadPoolTask = new ThreadPoolTaskExecutor();
        threadPoolTask.setCorePoolSize(corePoolSize);
        threadPoolTask.setMaxPoolSize(maxPoolSize);
        threadPoolTask.setQueueCapacity(queueCapacity);
        threadPoolTask.setKeepAliveSeconds(keepAlive);
        threadPoolTask.setAllowCoreThreadTimeOut(false);
        threadPoolTask.setThreadNamePrefix("task-");
        threadPoolTask.initialize();
        // Custom RejectedExecutionHandler
        threadPoolTask.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor executor) -> {
            try {
                logger.error("Task Rejected :- {}.", task.getClass().getCanonicalName());
                Thread.sleep(1000); // wait for 1 second if task is rejected
            } catch (InterruptedException ex) {
                logger.error("DAL Task Interrupted  :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            }
        });
        // Scheduler to log thread pool stats every minute
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            logger.info("Active: {}, PoolSize: {}, Core: {}, Max: {}, Queue: {}",
            threadPoolTask.getActiveCount(), threadPoolTask.getPoolSize(), threadPoolTask.getCorePoolSize(),
            threadPoolTask.getThreadPoolExecutor().getMaximumPoolSize(), threadPoolTask.getThreadPoolExecutor().getQueue().size());
        }, 5 * 60, 60, TimeUnit.SECONDS);
        logger.info(">============AsyncDALTaskExecutor End Successful============<");
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}