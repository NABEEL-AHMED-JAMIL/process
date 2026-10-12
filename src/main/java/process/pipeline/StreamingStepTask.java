package process.pipeline;

/**
 * A task that streams (MIG-344): it reads its input a batch at a time ({@link StreamContext#openInput}) and writes its
 * output a row at a time ({@link StreamContext#output}), so it holds a batch, not the table, and has no row or cell
 * limit. The engine runs {@link #stream}; {@link #run} -- the in-memory path a unit test or an older caller takes --
 * runs the same code over a dataset in memory ({@link Streams#inMemory}), so there is one implementation, not two.
 *
 * A streaming try returns {@link StepResult#streamed} when its output is in the sink, or {@link StepResult#nothing}
 * when it only acted (save, upload): the next step then reads what this one read, as before.
 */
public interface StreamingStepTask extends StepTask {

    StepResult stream(StreamContext context) throws Exception;

    @Override
    default StepResult run(StepContext context) throws Exception {
        return Streams.inMemory(this, context);
    }
}
