package process.util.validation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

import javax.persistence.Column;

/**
 * This AppUserValidation validate the information of the sheet
 * if the date not valid its stop the process and through the valid msg
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppUserValidation {

    // validation filed
    private final String REGEX_PASSWORD = "(?=.*\\d)(?=.*[a-z])(?=.*[A-Z])(?=.*[@$!%*#?&^_-]).{8,}";
    private final String REGEX_EMAIL = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    private Integer rowCounter = 0;
    private String errorMsg;

    private String firstName;
    private String lastName;
    private String timeZone;
    private String username;
    private String email;
    private String password;

    public AppUserValidation() {
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
        if (isNull(this.errorMsg)) {
            this.errorMsg = errorMsg;
        } else {
            this.errorMsg += errorMsg;
        }
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getTimeZone() {
        return timeZone;
    }

    public void setTimeZone(String timeZone) {
        this.timeZone = timeZone;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * This isValidJobDetail use to validate the
     * job detail of the job valid return true
     * if non-valid return false
     * @return boolean true|false
     * */
    public void isValidLookup() {
        if (this.isNull(this.firstName)) {
            this.setErrorMsg(String.format("FirstName should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.lastName))  {
            this.setErrorMsg(String.format("LastName should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.timeZone)) {
            this.setErrorMsg(String.format("TimeZone should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.username)) {
            this.setErrorMsg(String.format("Username should not be empty at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.email)) {
            this.setErrorMsg(String.format("Email should not be empty at row %s.<br>", rowCounter));
        } else if (!this.password.matches(this.REGEX_PASSWORD)) {
            this.setErrorMsg(String.format("Password should not be non space latter at row %s.<br>", rowCounter));
        }
        if (this.isNull(this.password)) {
            this.setErrorMsg(String.format("Password should not be empty at row %s.<br>", rowCounter));
        } else if (!this.password.matches(this.REGEX_PASSWORD)) {
            this.setErrorMsg(String.format("Password should not be non space latter at row %s.<br>", rowCounter));
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
