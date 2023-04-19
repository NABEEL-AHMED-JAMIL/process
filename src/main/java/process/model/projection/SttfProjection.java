package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface SttfProjection {

    public Long getSttFId();
    public String getSttFName();
    public String getDescription();
    public Long getStatus();
    public Boolean getSttFDefault();
    public Timestamp getDateCreated();
    public Long getTotalStt();
    public Long getTotalSection();
    public Long getTotalControl();

}
