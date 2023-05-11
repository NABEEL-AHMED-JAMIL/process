package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum FormType {

    SERVICE_FORM("SERVICE_FORM", 0l, "Service Form"),
    QUERY_FORM("QUERY_FORM", 1l, "Query Form");

    private String lookupType;
    private Long lookupValue;
    private String description;

    FormType(String lookupType, Long lookupValue, String description) {
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

    public Long getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(Long lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public static GLookup getFormTypeByValue(Long lookupValue) {
        FormType formType = null;
        if (lookupValue.equals(SERVICE_FORM.lookupValue)) {
            formType = SERVICE_FORM;
        } else if (lookupValue.equals(QUERY_FORM.lookupValue)) {
            formType = QUERY_FORM;
        }
        return new GLookup(formType.lookupType,
            formType.lookupValue, formType.description);
    }

    public static GLookup getFormTypeByDescription(String lookupValue) {
        FormType formType = null;
        if (lookupValue.equals(SERVICE_FORM.description)) {
            formType = SERVICE_FORM;
        } else if (lookupValue.equals(QUERY_FORM.description)) {
            formType = QUERY_FORM;
        }
        return new GLookup(formType.lookupType,
            formType.lookupValue, formType.description);
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
