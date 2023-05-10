package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.util.ProcessUtil;
import process.util.lookuputil.IsDefault;
import java.util.regex.Pattern;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class STTSValidation {

    private Pattern pattern = Pattern.compile("^[0-9]+$");

    private Integer rowCounter = 0;
    private String errorMsg;

    private String sectionOrder;
    private String sectionName;
    private String description;
    private String defaultSTTS;

    public STTSValidation() {
    }

    public Integer getRowCounter() {
        return rowCounter;
    }

    public void setRowCounter(Integer rowCounter) {
        this.rowCounter = rowCounter;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getSectionOrder() {
        return sectionOrder;
    }

    public void setSectionOrder(String sectionOrder) {
        this.sectionOrder = sectionOrder;
    }

    public String getSectionName() {
        return sectionName;
    }

    public void setSectionName(String sectionName) {
        this.sectionName = sectionName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDefaultSTTS() {
        return defaultSTTS;
    }

    public void setDefaultSTTS(String defaultSTTS) {
        this.defaultSTTS = defaultSTTS;
    }

    public void isValidSTTS() {
        try {
            if (this.isNull(this.sectionOrder)) {
                this.setErrorMsg(String.format("SectionOrder should not be empty at row %s.<br>", rowCounter));
            } else if (!this.pattern.matcher(sectionOrder).matches()) {
                this.setErrorMsg(String.format("SectionOrder type not correct at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.sectionName)) {
                this.setErrorMsg(String.format("SectionName should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.description)) {
                this.setErrorMsg(String.format("Description should not be empty at row %s.<br>", rowCounter));
            }
            if (this.isNull(this.defaultSTTS)) {
                this.setErrorMsg(String.format("Default should not be empty at row %s.<br>", rowCounter));
            } else if (ProcessUtil.isNull(IsDefault.getDefaultByDescription(this.defaultSTTS))) {
                this.setErrorMsg(String.format("Default type not correct at row %s.<br>", rowCounter));
            }
        } catch (Exception ex) {
            this.setErrorMsg(String.format(ex.getLocalizedMessage() + " at row %s.<br>", rowCounter));
        }
    }

    private static boolean isNull(String filed) {
        return (filed == null || filed.length() == 0) ? true : false;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
