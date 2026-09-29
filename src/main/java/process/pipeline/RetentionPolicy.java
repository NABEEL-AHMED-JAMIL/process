package process.pipeline;

import java.time.Duration;

/**
 * How long a run's datasets are kept (MIG-243): the pipeline's own datasetRetentionHours, and the workspace's data
 * policy for the pipeline's sensitivity (settings.sensitivity, internal when not said) -- read from ai-service, which
 * owns the policy ({@link PolicyRetention}).
 *
 * <b>The rule.</b> The policy's days are a ceiling: a pipeline that names its own hours keeps them, cut to the policy's
 * days when those are shorter; a pipeline that names none keeps the policy's days; with no policy days for the level
 * (or the policy not readable), the pipeline's hours or the default 24 stand -- as before MIG-243.
 */
@FunctionalInterface
public interface RetentionPolicy {

    /** The definition's hours alone, as before MIG-243: for an engine built without a policy (tests). */
    RetentionPolicy DEFINITION_ONLY = (tenantId, settings) -> combine(settings.getDatasetRetentionHours(), null);

    Duration retentionFor(long tenantId, PipelineDefinition.Settings settings);

    /** The rule: the definition's hours (null: none said) against the policy's days (null: none). */
    static Duration combine(Integer definitionHours, Integer policyDays) {
        Duration own = definitionHours == null ? null : Duration.ofHours(definitionHours);
        Duration policy = policyDays == null ? null : Duration.ofDays(policyDays);
        if (own != null && policy != null) {
            return own.compareTo(policy) <= 0 ? own : policy;
        }
        if (own != null) {
            return own;
        }
        return policy != null ? policy : Duration.ofHours(PipelineDefinition.Settings.DEFAULT_RETENTION_HOURS);
    }
}
