package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface SttProjection {

    public Long getSttId();
    public String getServiceName();
    public String getDescription();
    public Long getTaskType();
    public Long getStatus();
    public boolean getSttDefault();
    public Timestamp getDateCreated();
    public Long getTotalUser();
    public Long getTotalTask();
    public Long getTotalForm();

}