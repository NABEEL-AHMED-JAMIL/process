package process.engine.async.executor;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import process.util.exception.ExceptionUtil;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;

/**
 * @author Nabeel Ahmed
 */
public class AsyncDALTaskExecutor {

    public static Logger logger = LogManager.getLogger(AsyncDALTaskExecutor.class);

    private static LinkedBlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(1000);
    private static ThreadPoolExecutor threadPool;

    /**
     * This method use to add the task in thread pool
     * @param task
     * */
    public static void addTask(Runnable task) {
        try {
            logger.debug("Submitting Task of type : " + task.getClass().getCanonicalName());
            threadPool.submit(task);
        } catch (RejectedExecutionException ex) {
            logger.error("Failed to submit Task in queue " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * If max threads reach the limit the new thread reject state so its depend on the requirement
     * either rejected thread add again into the thread or else save in the db with the as inQueue status
     * @param minThreads
     * @param maxThreads
     * @param threadLifeInMins
     * */
    public AsyncDALTaskExecutor(Integer minThreads, Integer maxThreads, Integer threadLifeInMins) {
        logger.info(">============AsyncDALTaskExecutor Start Successful============<");
        threadPool = new ThreadPoolExecutor(minThreads, maxThreads, threadLifeInMins, TimeUnit.MINUTES, queue);
        threadPool.setRejectedExecutionHandler((Runnable task, ThreadPoolExecutor executor) -> {
            try {
                logger.error("Task Rejected : " + task.getClass().getCanonicalName());
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                logger.error("DAL Task Interrupted " + ExceptionUtil.getRootCauseMessage(ex));
            }
            // if task reject then add same take for execution again
            executor.execute(task);
        });
        // scheduler use to check how man thread are active and other pool size detail
        (new Timer()).schedule(new TimerTask() {
            @Override public void run() {
                logger.info("AsyncDAL Active No Threads: " + threadPool.getActiveCount() +
                " Core Pool size of Threads: " + threadPool.getCorePoolSize() +
                " Current no of threads in pool: " + threadPool.getPoolSize() +
                " Current Queue Size: " + queue.size() + " Max allowed Threads: "+threadPool.getMaximumPoolSize());
            }
        }, 5 * 60 * 1000, 60000);
        logger.info(">============AsyncDALTaskExecutor End Successful============<");
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}