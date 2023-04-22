package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTProjection {

    public Long getSttId();
    public String getServiceName();
    public String getDescription();
    public Long getTaskType();
    public Long getStatus();
    public Boolean getSttDefault();
    public Timestamp getDateCreated();
    public Long getTotalUser();
    public Long getTotalTask();
    public Long getTotalForm();

}