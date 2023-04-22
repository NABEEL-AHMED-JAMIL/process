package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum FormControlType {

    WEEK("WEEK", "week", "Week"),
    RANGE("RANGE", "range", "Range"),
    HIDDEN("HIDDEN", "hidden", "Hidden"),
    FILE("FILE", "file", "File"),
    DATE("DATE", "date", "Date"),
    EMAIL("EMAIL","email", "Email"),
    TEL("TEL", "tel", "Tel"),
    MONTH("MONTH", "month", "Month"),
    PASSWORD("PASSWORD", "password", "Password"),
    URL("URL", "url", "Url"),
    DATETIME_LOCAL("DATETIME_LOCAL", "datetime-local", "DateTime Local"),
    NUMBER("NUMBER", "number", "Number"),
    RADIO("RADIO", "radio", "Radio"),
    COLOR("COLOR", "color", "Color"),
    TIME("TIME", "time", "Time"),
    TEXT("TEXT", "text", "Text");

    private String lookupType;
    private String lookupValue;
    private String description;

    FormControlType(String lookupType, String lookupValue, String description) {
        this.lookupType = lookupType;
        this.lookupValue = lookupValue;
        this.description = description;
    }

    public String getLookupType() {
        return lookupType;
    }

    public void setLookupType(String lookupType) {
        this.lookupType = lookupType;
    }

    public String getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(String lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
