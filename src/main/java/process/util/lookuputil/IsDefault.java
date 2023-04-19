package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public enum IsDefault {

    NO_DEFAULT("NO_DEFAULT", false, "No"),
    YES_DEFAULT("YES_DEFAULT", true, "Yes");

    private String lookupType;
    private Boolean lookupValue;
    private String description;

    IsDefault(String lookupType, Boolean lookupValue, String description) {
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

    public Boolean getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(Boolean lookupValue) {
        this.lookupValue = lookupValue;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public static GLookup getDefaultByValue(boolean lookupValue) {
        IsDefault aDefault = null;
         if (!lookupValue) {
            aDefault = NO_DEFAULT;
        } else if (lookupValue) {
             aDefault = YES_DEFAULT;
         }
        return new GLookup(aDefault.lookupType, aDefault.lookupValue, aDefault.description);
    }


    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
