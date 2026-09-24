package process.model.projection;

import java.sql.Timestamp;

/**
 * One pipeline as the list screens read it: the row's own columns plus how many fields it
 * carries, and none of the fields themselves. The entity loads every field eagerly (the form
 * editor needs them), which at ten thousand pipelines was twenty megabytes for a list that
 * shows two tags and a count; the fields come through {@code pipeline.json/fields} when a row
 * is opened.
 */
public interface PipelineRowProjection {
    Long getPipelineKey();
    String getPipelineId();
    String getPipelineName();
    String getDescription();
    Long getTenantId();
    Long getSourceTaskTypeId();
    String getStatus();
    /** The instant; PipelineRowDto carries its Chicago wall-clock. */
    Timestamp getDateCreated();
    Long getCreatedBy();
    Long getUpdatedBy();
    Long getFieldCount();
    Long getRequiredCount();
}
