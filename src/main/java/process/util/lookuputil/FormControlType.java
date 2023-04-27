package process.util.lookuputil;

import com.google.gson.Gson;
import process.util.ProcessUtil;

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
    TEXT("TEXT", "text", "Text"),
    SELECT("SELECT", "select", "Select"),
    MULTI_SELECT("MULTI_SELECT", "multi-select", "Multi Select");


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

    public static GLookup getFormControlTypeByValue(String lookupValue) {
        FormControlType formControlType = null;
        if (lookupValue.equals(WEEK.lookupValue)) {
            formControlType = WEEK;
        } else if (lookupValue.equals(RANGE.lookupValue)) {
            formControlType = RANGE;
        } else if (lookupValue.equals(HIDDEN.lookupValue)) {
            formControlType = HIDDEN;
        } else if (lookupValue.equals(FILE.lookupValue)) {
            formControlType = FILE;
        } else if (lookupValue.equals(DATE.lookupValue)) {
            formControlType = DATE;
        } else if (lookupValue.equals(EMAIL.lookupValue)) {
            formControlType = EMAIL;
        } else if (lookupValue.equals(TEL.lookupValue)) {
            formControlType = TEL;
        } else if (lookupValue.equals(MONTH.lookupValue)) {
            formControlType = MONTH;
        } else if (lookupValue.equals(PASSWORD.lookupValue)) {
            formControlType = PASSWORD;
        } else if (lookupValue.equals(URL.lookupValue)) {
            formControlType = URL;
        } else if (lookupValue.equals(DATETIME_LOCAL.lookupValue)) {
            formControlType = DATETIME_LOCAL;
        } else if (lookupValue.equals(NUMBER.lookupValue)) {
            formControlType = NUMBER;
        } else if (lookupValue.equals(RADIO.lookupValue)) {
            formControlType = RADIO;
        } else if (lookupValue.equals(COLOR.lookupValue)) {
            formControlType = COLOR;
        } else if (lookupValue.equals(TIME.lookupValue)) {
            formControlType = TIME;
        } else if (lookupValue.equals(TEXT.lookupValue)) {
            formControlType = TEXT;
        } else if (lookupValue.equals(SELECT.lookupValue)) {
            formControlType = SELECT;
        } else if (lookupValue.equals(MULTI_SELECT.lookupValue)) {
            formControlType = MULTI_SELECT;
        }
        return new GLookup(formControlType.lookupType,
            formControlType.lookupValue, formControlType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
