package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTFProjection {

    public Long getSttfId();
    public String getSttfName();
    public String getDescription();
    public Long getStatus();
    public Long getFormType();
    public Boolean getSttFDefault();
    public String getHomePage();
    public String getServiceId();
    public Timestamp getDateCreated();
    public Long getTotalStt();
    public Long getTotalSection();
    public Long getTotalControl();

}
