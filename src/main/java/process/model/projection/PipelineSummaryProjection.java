package process.model.projection;

/** The Pipelines screen's tiles: how many, how many active, on how many topics, how many fields, how many without a topic. */
public interface PipelineSummaryProjection {
    Long getTotal();
    Long getActive();
    Long getTopics();
    Long getFields();
    Long getUntopped();
}
