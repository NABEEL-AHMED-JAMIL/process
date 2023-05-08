package process.util.lookuputil;

import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
public class GLookup {

    private String lookupType;
    private Object lookupValue;
    private String description;

    public GLookup(String lookupType, Object lookupValue, String description) {
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

    public Object getLookupValue() {
        return lookupValue;
    }

    public void setLookupValue(Object lookupValue) {
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
