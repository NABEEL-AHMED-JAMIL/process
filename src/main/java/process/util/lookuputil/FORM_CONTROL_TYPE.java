package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum FORM_CONTROL_TYPE {

    WEEK("WEEK", "week", "Week"),
    RANGE("RANGE", "range", "Range"),
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
    CHECKBOX("CHECKBOX", "checkbox", "Checkbox"),
    COLOR("COLOR", "color", "Color"),
    TIME("TIME", "time", "Time"),
    TEXT("TEXT", "text", "Text"),
    TEXTAREA("TEXTAREA", "textarea", "TextArea"),
    SELECT("SELECT", "select", "Select"),
    MULTI_SELECT("MULTI_SELECT", "multi-select", "Multi Select");

    private String lookupType;
    private String lookupValue;
    private String description;

    FORM_CONTROL_TYPE(String lookupType, String lookupValue, String description) {
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
        FORM_CONTROL_TYPE formControlType = null;
        if (lookupValue.equals(WEEK.lookupValue)) {
            formControlType = WEEK;
        } else if (lookupValue.equals(RANGE.lookupValue)) {
            formControlType = RANGE;
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
        } else if (lookupValue.equals(CHECKBOX.lookupValue)) {
            formControlType = CHECKBOX;
        } else if (lookupValue.equals(COLOR.lookupValue)) {
            formControlType = COLOR;
        } else if (lookupValue.equals(TIME.lookupValue)) {
            formControlType = TIME;
        } else if (lookupValue.equals(TEXT.lookupValue)) {
            formControlType = TEXT;
        } else if (lookupValue.equals(TEXTAREA.lookupValue)) {
            formControlType = TEXTAREA;
        } else if (lookupValue.equals(SELECT.lookupValue)) {
            formControlType = SELECT;
        } else if (lookupValue.equals(MULTI_SELECT.lookupValue)) {
            formControlType = MULTI_SELECT;
        }
        return new GLookup(formControlType.lookupType,
                formControlType.lookupValue, formControlType.description);
    }

    public static GLookup getFormControlTypeByDescription(String lookupValue) {
        FORM_CONTROL_TYPE formControlType = null;
        if (lookupValue.equals(WEEK.description)) {
            formControlType = WEEK;
        } else if (lookupValue.equals(RANGE.description)) {
            formControlType = RANGE;
        } else if (lookupValue.equals(FILE.description)) {
            formControlType = FILE;
        } else if (lookupValue.equals(DATE.description)) {
            formControlType = DATE;
        } else if (lookupValue.equals(EMAIL.description)) {
            formControlType = EMAIL;
        } else if (lookupValue.equals(TEL.description)) {
            formControlType = TEL;
        } else if (lookupValue.equals(MONTH.description)) {
            formControlType = MONTH;
        } else if (lookupValue.equals(PASSWORD.description)) {
            formControlType = PASSWORD;
        } else if (lookupValue.equals(URL.description)) {
            formControlType = URL;
        } else if (lookupValue.equals(DATETIME_LOCAL.description)) {
            formControlType = DATETIME_LOCAL;
        } else if (lookupValue.equals(NUMBER.description)) {
            formControlType = NUMBER;
        } else if (lookupValue.equals(RADIO.description)) {
            formControlType = RADIO;
        } else if (lookupValue.equals(CHECKBOX.description)) {
            formControlType = CHECKBOX;
        } else if (lookupValue.equals(COLOR.description)) {
            formControlType = COLOR;
        } else if (lookupValue.equals(TIME.description)) {
            formControlType = TIME;
        } else if (lookupValue.equals(TEXT.description)) {
            formControlType = TEXT;
        } else if (lookupValue.equals(TEXTAREA.description)) {
            formControlType = TEXTAREA;
        } else if (lookupValue.equals(SELECT.description)) {
            formControlType = SELECT;
        } else if (lookupValue.equals(MULTI_SELECT.description)) {
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
