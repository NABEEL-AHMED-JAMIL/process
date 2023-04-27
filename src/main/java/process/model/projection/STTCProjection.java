package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTCProjection {

    public Long getSttCId();
    public Long getSttCOrder();
    public String getSttCName();
    public String getFiledName();
    public String getFiledType();
    public Long getStatus();
    public Boolean getSTTCDefault();
    public Boolean getMandatory();
    public Timestamp getDateCreated();
    public Long getTotalStt();
    public Long getTotalForm();
    public Long getTotalSection();

}
