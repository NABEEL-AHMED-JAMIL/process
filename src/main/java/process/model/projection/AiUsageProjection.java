package process.model.projection;

import java.sql.Timestamp;

/** One prompt's use over a range, for the Reports page. */
public interface AiUsageProjection {
    Long getPromptId();
    String getPromptName();
    Long getCalls();
    Long getFailed();
    Long getTries();
    Long getTokensIn();
    Long getTokensOut();
    Double getMedianMs();
    Timestamp getLastAt();
}
