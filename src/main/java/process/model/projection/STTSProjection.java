package process.model.projection;

import java.sql.Timestamp;

/**
 * @author Nabeel Ahmed
 */
public interface STTSProjection {

    public Long getSttsId();
    public String getSttsName();
    public String getDescription();
    public Long getStatus();
    public Boolean getSttsDefault();
    public Timestamp getDateCreated();
    public Long getTotalSTT();
    public Long getTotalForm();
    public Long getTotalControl();

}
