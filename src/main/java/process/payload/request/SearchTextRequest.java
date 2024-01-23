package process.payload.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchTextRequest {

    private String itemName;
    private Object itemValue;

    public SearchTextRequest() {}

    public String getItemName() {
        return itemName;
    }
    public void setItemName(String itemName) {
        this.itemName = itemName;
    }

    public Object getItemValue() {
        return itemValue;
    }
    public void setItemValue(Object itemValue) {
        this.itemValue = itemValue;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}
