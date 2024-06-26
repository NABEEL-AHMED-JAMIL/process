package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTCProjection {

    public Long getSttcId();
    public String getSttcName();
    public String getFieldName();
    public String getFieldType();
    public Long getStatus();
    public Boolean getSttcDefault();
    public Boolean getMandatory();
    public Timestamp getDateCreated();
    public Long getTotalStt();
    public Long getTotalForm();
    public Long getTotalSection();

}
