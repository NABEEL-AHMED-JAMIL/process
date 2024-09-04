package process.engine.async.executor;

/**
 * @author Nabeel Ahmed
 */
public class PrioritizedTask implements Runnable, Comparable<PrioritizedTask> {

    private final Runnable task;
    private final int priority;

    public PrioritizedTask(Runnable task, int priority) {
        this.task = task;
        this.priority = priority;
    }

    @Override
    public void run() {
        task.run();
    }

    @Override
    public int compareTo(PrioritizedTask o) {
        return Integer.compare(o.priority, this.priority); // Higher priority first
    }
}
