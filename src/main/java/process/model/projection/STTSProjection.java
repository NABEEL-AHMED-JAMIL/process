package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTSProjection {

    public Long getSttSId();
    public String getSttSName();
    public String getDescription();
    public Long getSttSOrder();
    public Long getStatus();
    public Boolean getSttSDefault();
    public Timestamp getDateCreated();
    public Long getTotalSTT();
    public Long getTotalForm();
    public Long getTotalControl();

}
