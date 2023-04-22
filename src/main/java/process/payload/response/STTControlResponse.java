package process.payload.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.pojo.AppUser;
import process.model.pojo.AppUserSTTC;
import process.util.lookuputil.GLookup;

import javax.persistence.*;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTControlResponse {

    private Long sttCId;
    private Long sttCOrder;
    private String sttCName;
    private String description;
    private GLookup filedType;
    private String filedTitle;
    private String filedName;
    private String placeHolder;
    private Long filedWidth;
    private Long minLength;
    private Long maxLength;
    private Long filedLookUp;
    private boolean separateLine;
    private boolean mandatory;
    private GLookup defaultSttC;
    private String pattern;
    private GLookup status;
    private boolean filedNewLine;

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
